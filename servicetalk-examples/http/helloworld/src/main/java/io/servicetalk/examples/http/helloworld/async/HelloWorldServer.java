/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.examples.http.helloworld.async;

import io.servicetalk.client.api.ConnectionRejectedException;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.http.netty.HttpServers;
import io.servicetalk.transport.api.ConnectionAcceptor;
import io.servicetalk.transport.api.ConnectionAcceptorFactory;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.ReducedConnectionInfo;

import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.api.HttpSerializers.textSerializerUtf8;

public final class HelloWorldServer {
    public static void main(String[] args) throws Exception {
        HttpServers.forPort(8080)
                .appendConnectionAcceptorFilter(original -> new ConnectionAcceptor() {
                    @Override
                    public Completable accept(final ConnectionContext context) {
                        System.err.println("FULL: " + Thread.currentThread().getName());
                        return Completable.completed();
                    }

                    @Override
                    public Completable accept(final ReducedConnectionInfo connectionInfo) {
                        System.err.println("RCI: " + Thread.currentThread().getName());
                        return Completable.completed();

                        //return Completable.failed(new RuntimeException("I FAILED YOU"));
                    }

                })
                .listenAndAwait((ctx, request, responseFactory) ->
                        succeeded(responseFactory.ok()
                                .payloadBody("Hello World!", textSerializerUtf8())))
                .awaitShutdown();
    }
}
