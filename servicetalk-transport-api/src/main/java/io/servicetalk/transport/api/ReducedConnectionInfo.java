/*
 * Copyright © 2023 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.transport.api;

import java.net.SocketAddress;

/**
 * Provides essential information about a connection.
 */
public interface ReducedConnectionInfo {
    /**
     * The {@link SocketAddress} to which the associated connection is bound.
     *
     * @return The {@link SocketAddress} to which the associated connection is bound.
     */
    SocketAddress localAddress();

    /**
     * The {@link SocketAddress} to which the associated connection is connected.
     *
     * @return The {@link SocketAddress} to which the associated connection is connected.
     */
    SocketAddress remoteAddress();

    /**
     * Get the {@link ExecutionContext} for this {@link ReducedConnectionInfo}.
     * <p>
     * The {@link ExecutionContext#ioExecutor()} will represent the thread responsible for IO for this
     * {@link ReducedConnectionInfo}. Note that this maybe different that what was used to create this object because
     * at this time a specific {@link IoExecutor} has been selected.
     *
     * @return the {@link ExecutionContext} for this {@link ReducedConnectionInfo}.
     */
    ExecutionContext<?> executionContext();
}
