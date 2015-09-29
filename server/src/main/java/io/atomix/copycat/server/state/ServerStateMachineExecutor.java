/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.atomix.copycat.server.state;

import io.atomix.copycat.client.Operation;
import io.atomix.copycat.client.error.ApplicationException;
import io.atomix.copycat.server.Commit;
import io.atomix.copycat.server.StateMachineExecutor;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.concurrent.Scheduled;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Raft server state machine executor.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
class ServerStateMachineExecutor implements StateMachineExecutor {
  private final ThreadContext executor;
  private final ServerStateMachineContext context;
  private final List<ServerScheduledTask> tasks = new ArrayList<>();
  private final List<ServerScheduledTask> complete = new ArrayList<>();
  private final Map<Class, Function> operations = new HashMap<>();
  private Function allOperation;
  private long timestamp;

  ServerStateMachineExecutor(ThreadContext context) {
    this.executor = context;
    this.context = new ServerStateMachineContext();
  }

  /**
   * Returns the executor timestamp.
   */
  long timestamp() {
    return timestamp;
  }

  @Override
  public ServerStateMachineContext context() {
    return context;
  }

  @Override
  public Logger logger() {
    return executor.logger();
  }

  @Override
  public Serializer serializer() {
    return executor.serializer();
  }

  @Override
  public Executor executor() {
    return executor.executor();
  }

  /**
   * Executes the given command commit on the state machine.
   */
  <T extends Operation<U>, U> CompletableFuture<U> executeCommand(Commit<T> commit) {
    CompletableFuture<U> future = new CompletableFuture<>();
    executor.executor().execute(() -> {
      context.update(commit.index(), commit.time(), ServerStateMachineContext.Type.COMMAND);
      executeOperation(commit, future);
    });
    return future;
  }

  /**
   * Executes the given query commit on the state machine.
   */
  <T extends Operation<U>, U> CompletableFuture<U> executeQuery(Commit<T> commit) {
    CompletableFuture<U> future = new CompletableFuture<>();
    executor.executor().execute(() -> {
      context.update(commit.index(), commit.time(), ServerStateMachineContext.Type.QUERY);
      executeOperation(commit, future);
    });
    return future;
  }

  /**
   * Executes an operation.
   */
  @SuppressWarnings("unchecked")
  private <T extends Operation<U>, U> void executeOperation(Commit commit, CompletableFuture<U> future) {
    // Get the function registered for the operation. If no function is registered, attempt to
    // use a global function if available.
    Function function = operations.get(commit.type());

    if (function == null) {
      // If no operation function was found for the class, try to find an operation function
      // registered with a parent class.
      for (Map.Entry<Class, Function> entry : operations.entrySet()) {
        if (entry.getKey().isAssignableFrom(commit.type())) {
          function = entry.getValue();
          break;
        }
      }

      // If a parent operation function was found, store the function for future reference.
      if (function != null) {
        operations.put(commit.type(), function);
      }
    }

    // If no operation function was found, use the all operation and store it as the permanent operation.
    if (function == null) {
      function = allOperation;
      if (function != null) {
        operations.put(commit.type(), function);
      }
    }

    if (function == null) {
      future.completeExceptionally(new IllegalStateException("unknown state machine operation: " + commit.type()));
    } else {
      // Execute the operation. If the operation return value is a Future, await the result,
      // otherwise immediately complete the execution future.
      try {
        future.complete((U) function.apply(commit));
      } catch (Exception e) {
        future.completeExceptionally(new ApplicationException("An application error occurred", e));
      }
    }
  }

  /**
   * Executes scheduled callbacks based on the provided time.
   */
  void tick(long timestamp) {
    this.timestamp = Math.max(this.timestamp, timestamp);

    // Only create an iterator if there are actually tasks scheduled.
    if (!tasks.isEmpty()) {

      // Iterate through scheduled tasks until we reach a task that has not met its scheduled time.
      // The tasks list is sorted by time on insertion.
      Iterator<ServerScheduledTask> iterator = tasks.iterator();
      while (iterator.hasNext()) {
        ServerScheduledTask task = iterator.next();
        if (task.complete(this.timestamp)) {
          executor.executor().execute(() -> {
            context.update(context.version(), Instant.ofEpochMilli(task.time), ServerStateMachineContext.Type.NONE);
            task.execute();
          });
          complete.add(task);
          iterator.remove();
        } else {
          break;
        }
      }

      // Iterate through tasks that were completed and reschedule them.
      for (ServerScheduledTask task : complete) {
        task.reschedule();
      }
      complete.clear();
    }
  }

  @Override
  public CompletableFuture<Void> execute(Runnable callback) {
    return executor.execute(callback);
  }

  @Override
  public <T> CompletableFuture<T> execute(Supplier<T> callback) {
    return executor.execute(callback);
  }

  @Override
  public Scheduled schedule(Duration delay, Runnable callback) {
    Assert.state(context.type() != ServerStateMachineContext.Type.QUERY, "cannot schedule callbacks during query execution");
    return new ServerScheduledTask(callback, delay.toMillis()).schedule();
  }

  @Override
  public Scheduled schedule(Duration initialDelay, Duration interval, Runnable callback) {
    Assert.state(context.type() != ServerStateMachineContext.Type.QUERY, "cannot schedule callbacks during query execution");
    return new ServerScheduledTask(callback, initialDelay.toMillis(), interval.toMillis()).schedule();
  }

  @Override
  public StateMachineExecutor register(Function<Commit<? extends Operation<?>>, ?> callback) {
    allOperation = Assert.notNull(callback, "callback");
    return this;
  }

  @Override
  public <T extends Operation<Void>> StateMachineExecutor register(Class<T> type, Consumer<Commit<T>> callback) {
    Assert.notNull(type, "type");
    Assert.notNull(callback, "callback");
    operations.put(type, (Function<Commit<T>, Void>) commit -> {
      callback.accept(commit);
      return null;
    });
    return this;
  }

  @Override
  public <T extends Operation<U>, U> StateMachineExecutor register(Class<T> type, Function<Commit<T>, U> callback) {
    Assert.notNull(type, "type");
    Assert.notNull(callback, "callback");
    operations.put(type, callback);
    return this;
  }

  @Override
  public void close() {
    executor.close();
  }

  /**
   * Scheduled task.
   */
  private class ServerScheduledTask implements Scheduled {
    private final long delay;
    private final long interval;
    private final Runnable callback;
    private long time;

    private ServerScheduledTask(Runnable callback, long delay) {
      this(callback, delay, 0);
    }

    private ServerScheduledTask(Runnable callback, long delay, long interval) {
      this.delay = delay;
      this.interval = interval;
      this.callback = callback;
      this.time = context.now().toEpochMilli() + delay;
    }

    /**
     * Schedules the task.
     */
    private Scheduled schedule() {
      // Perform binary search to insert the task at the appropriate position in the tasks list.
      if (tasks.isEmpty()) {
        tasks.add(this);
      } else {
        int l = 0;
        int u = tasks.size() - 1;
        int i;
        while (true) {
          i = (u + l) / 2;
          long t = tasks.get(i).time;
          if (t == time) {
            tasks.add(i, this);
            return this;
          } else if (t < time) {
            l = i + 1;
            if (l > u) {
              tasks.add(i + 1, this);
              return this;
            }
          } else {
            u = i - 1;
            if (l > u) {
              tasks.add(i, this);
              return this;
            }
          }
        }
      }
      return this;
    }

    /**
     * Reschedules the task.
     */
    private void reschedule() {
      if (interval > 0) {
        time = timestamp + delay;
        schedule();
      }
    }

    /**
     * Returns a boolean value indicating whether the task delay has been met.
     */
    private boolean complete(long timestamp) {
      return timestamp > time;
    }

    /**
     * Executes the task.
     */
    private synchronized void execute() {
      callback.run();
    }

    @Override
    public synchronized void cancel() {
      tasks.remove(this);
    }
  }

}
