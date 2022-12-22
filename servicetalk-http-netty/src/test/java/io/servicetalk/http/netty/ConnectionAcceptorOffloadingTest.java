/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpResponseFactory;
import io.servicetalk.http.api.HttpResponseStatus;
import io.servicetalk.http.api.HttpServiceContext;
import io.servicetalk.transport.api.ConnectionAcceptor;
import io.servicetalk.transport.api.ConnectionAcceptorFactory;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.IoThreadFactory;
import io.servicetalk.transport.api.ReducedConnectionInfo;
import io.servicetalk.transport.api.ServerContext;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.api.HttpSerializers.textSerializerUtf8;
import static io.servicetalk.transport.api.ConnectExecutionStrategy.offloadAll;
import static io.servicetalk.transport.api.ConnectExecutionStrategy.offloadNone;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

class ConnectionAcceptorOffloadingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testAcceptorOffloading(boolean offload) throws Exception {
        AtomicReference<Boolean> offloadedFull = new AtomicReference<>();
        AtomicReference<Boolean> offloadedEarly = new AtomicReference<>();

        ConnectionAcceptorFactory factory = ConnectionAcceptorFactory.withStrategy(original ->
                new ConnectionAcceptor() {
                    @Override
                    public Completable accept(final ConnectionContext context) {
                        boolean isIoThread = IoThreadFactory.IoThread.currentThreadIsIoThread();
                        offloadedFull.set(!isIoThread);
                        return original.accept(context);
                    }

                    @Override
                    public Completable accept(final ReducedConnectionInfo info) {
                        boolean isIoThread = IoThreadFactory.IoThread.currentThreadIsIoThread();
                        offloadedEarly.set(!isIoThread);
                        return original.accept(info);
                    }
                }, offload ? offloadAll() : offloadNone());

        try (ServerContext server = HttpServers.forPort(0)
                .appendConnectionAcceptorFilter(factory)
                .listenAndAwait(this::helloWorld)) {
            SocketAddress serverAddress = server.listenAddress();

            try (BlockingHttpClient client = HttpClients.forResolvedAddress(serverAddress).buildBlocking()) {
                HttpResponse response = client.request(client.get("/sayHello"));
                assertThat("unexpected status", response.status(), is(HttpResponseStatus.OK));
            }
        }
        assertThat("factory was not invoked", offloadedFull.get(), is(notNullValue()));
        assertThat("incorrect full offloading", offloadedFull.get(), is(offload));
        assertThat("factory was not invoked", offloadedEarly.get(), is(notNullValue()));
        assertThat("incorrect early offloading", offloadedEarly.get(), is(offload));
    }

    private Single<HttpResponse> helloWorld(HttpServiceContext ctx,
                                            HttpRequest request,
                                            HttpResponseFactory responseFactory) {
        return succeeded(responseFactory.ok().payloadBody("Hello World!", textSerializerUtf8()));
    }
}
