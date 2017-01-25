/*
 * Copyright 2016 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.copycat.protocol.response;

import io.atomix.copycat.protocol.websocket.request.WebSocketKeepAliveRequest;
import io.atomix.copycat.protocol.websocket.request.WebSocketUnregisterRequest;

import java.util.Objects;

/**
 * Session unregister response.
 * <p>
 * Session unregister responses are sent in response to a {@link WebSocketUnregisterRequest}.
 * If the response is successful, that indicates the session was successfully unregistered. For unsuccessful
 * unregister requests, sessions can still be expired by simply halting {@link WebSocketKeepAliveRequest}s
 * to the cluster.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class UnregisterResponse extends SessionResponse {
  protected UnregisterResponse(Status status, ProtocolResponse.Error error) {
    super(status, error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), status);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof UnregisterResponse) {
      UnregisterResponse response = (UnregisterResponse) object;
      return response.status == status;
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("%s[status=%s]", getClass().getSimpleName(), status);
  }

  /**
   * Status response builder.
   */
  public static class Builder extends SessionResponse.Builder<UnregisterResponse.Builder, UnregisterResponse> {
    @Override
    public UnregisterResponse copy(UnregisterResponse response) {
      return new UnregisterResponse(response.status, response.error);
    }

    @Override
    public UnregisterResponse build() {
      return new UnregisterResponse(status, error);
    }
  }
}
