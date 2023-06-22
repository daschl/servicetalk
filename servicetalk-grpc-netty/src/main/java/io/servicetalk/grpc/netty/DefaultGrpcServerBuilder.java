/*
 * Copyright © 2019-2023 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.grpc.netty;

import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.grpc.api.GrpcBindableService;
import io.servicetalk.grpc.api.GrpcExceptionMapperServiceFilter;
import io.servicetalk.grpc.api.GrpcExecutionStrategy;
import io.servicetalk.grpc.api.GrpcLifecycleObserver;
import io.servicetalk.grpc.api.GrpcServerBuilder;
import io.servicetalk.grpc.api.GrpcServerContext;
import io.servicetalk.grpc.api.GrpcServiceFactory;
import io.servicetalk.grpc.api.GrpcServiceFactory.ServerBinder;
import io.servicetalk.http.api.BlockingHttpService;
import io.servicetalk.http.api.BlockingStreamingHttpService;
import io.servicetalk.http.api.DelegatingHttpServerBuilder;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpServerBuilder;
import io.servicetalk.http.api.HttpServerContext;
import io.servicetalk.http.api.HttpService;
import io.servicetalk.http.api.StreamingHttpService;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.netty.internal.ExecutionContextBuilder;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.internal.FutureUtils.awaitResult;
import static io.servicetalk.grpc.api.GrpcExecutionStrategies.defaultStrategy;
import static io.servicetalk.grpc.api.GrpcFilters.newGrpcDeadlineServerFilterFactory;
import static io.servicetalk.http.netty.HttpProtocolConfigs.h2Default;
import static io.servicetalk.utils.internal.DurationUtils.ensurePositive;
import static java.util.Objects.requireNonNull;

final class DefaultGrpcServerBuilder implements GrpcServerBuilder, ServerBinder {
    private final Supplier<HttpServerBuilder> httpServerBuilderSupplier;
    private GrpcServerBuilder.HttpInitializer initializer = builder -> {
        // no-op
    };
    private GrpcServerBuilder.HttpInitializer directCallInitializer = builder -> {
        // no-op
    };

    @Nullable
    private ExecutionContextInterceptorHttpServerBuilder interceptorBuilder;

    /**
     * A duration greater than zero or null for no timeout.
     */
    @Nullable
    private Duration defaultTimeout;
    private boolean appendTimeoutFilter = true;

    // Do not use this ctor directly, GrpcServers is the entry point for creating a new builder.
    DefaultGrpcServerBuilder(final Supplier<HttpServerBuilder> httpServerBuilderSupplier) {
        this.httpServerBuilderSupplier = () ->
                requireNonNull(httpServerBuilderSupplier.get(), "Supplier<HttpServerBuilder> result was null")
                    .protocols(h2Default()).allowDropRequestTrailers(true);
    }

    @Override
    public GrpcServerBuilder initializeHttp(final GrpcServerBuilder.HttpInitializer initializer) {
        this.initializer = requireNonNull(initializer);
        return this;
    }

    @Override
    public GrpcServerBuilder defaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = ensurePositive(defaultTimeout, "defaultTimeout");
        return this;
    }

    @Override
    public GrpcServerBuilder defaultTimeout(@Nullable final Duration defaultTimeout,
                                            final boolean appendTimeoutFilter) {
        this.defaultTimeout = defaultTimeout == null ? null : ensurePositive(defaultTimeout, "defaultTimeout");
        this.appendTimeoutFilter = appendTimeoutFilter;
        return this;
    }

    @Override
    public GrpcServerBuilder lifecycleObserver(final GrpcLifecycleObserver lifecycleObserver) {
        directCallInitializer = directCallInitializer.append(builder -> builder
                .lifecycleObserver(new GrpcToHttpLifecycleObserverBridge(lifecycleObserver)));
        return this;
    }

    @Override
    public Single<GrpcServerContext> listen(GrpcBindableService<?>... services) {
        GrpcServiceFactory<?>[] factories = Arrays.stream(services)
                .map(GrpcBindableService::bindService)
                .toArray(GrpcServiceFactory<?>[]::new);
        return listen(factories);
    }

    @Override
    public Single<GrpcServerContext> listen(GrpcServiceFactory<?>... serviceFactories) {
        return doListen(GrpcServiceFactory.merge(serviceFactories));
    }

    @Override
    public GrpcServerContext listenAndAwait(GrpcServiceFactory<?>... serviceFactories) throws Exception {
        return awaitResult(listen(serviceFactories).toFuture());
    }

    @Override
    public GrpcServerContext listenAndAwait(GrpcBindableService<?>... services) throws Exception {
        GrpcServiceFactory<?>[] factories = Arrays.stream(services)
                .map(GrpcBindableService::bindService)
                .toArray(GrpcServiceFactory<?>[]::new);
        return listenAndAwait(factories);
    }

    /**
     * Starts this server and returns the {@link GrpcServerContext} after the server has been successfully started.
     * <p>
     * If the underlying protocol (eg. TCP) supports it this will result in a socket bind/listen on {@code address}.
     *
     * @param serviceFactory {@link GrpcServiceFactory} to create a <a href="https://www.grpc.io">gRPC</a> service.
     * @return A {@link GrpcServerContext} by blocking the calling thread until the server is successfully started or
     * throws an {@link Exception} if the server could not be started.
     */
    private Single<GrpcServerContext> doListen(final GrpcServiceFactory<?> serviceFactory) {
        interceptorBuilder = preBuild();
        return serviceFactory.bind(this, interceptorBuilder.contextBuilder.build());
    }

    private ExecutionContextInterceptorHttpServerBuilder preBuild() {
        final ExecutionContextInterceptorHttpServerBuilder interceptor =
                new ExecutionContextInterceptorHttpServerBuilder(httpServerBuilderSupplier.get());

        interceptor.appendNonOffloadingServiceFilter(GrpcExceptionMapperServiceFilter.INSTANCE);

        directCallInitializer.initialize(interceptor);
        if (appendTimeoutFilter) {
            interceptor.appendNonOffloadingServiceFilter(newGrpcDeadlineServerFilterFactory(defaultTimeout));
        }
        initializer.initialize(interceptor);

        return interceptor;
    }

    @Override
    public Single<HttpServerContext> bind(final HttpService service) {
        return interceptorBuilder.listen(service);
    }

    @Override
    public Single<HttpServerContext> bindStreaming(final StreamingHttpService service) {
        return interceptorBuilder.listenStreaming(service);
    }

    @Override
    public Single<HttpServerContext> bindBlocking(final BlockingHttpService service) {
        return interceptorBuilder.listenBlocking(service);
    }

    @Override
    public Single<HttpServerContext> bindBlockingStreaming(final BlockingStreamingHttpService service) {
        return interceptorBuilder.listenBlockingStreaming(service);
    }

    private static class ExecutionContextInterceptorHttpServerBuilder extends DelegatingHttpServerBuilder {
        private final ExecutionContextBuilder<GrpcExecutionStrategy> contextBuilder =
                new ExecutionContextBuilder<GrpcExecutionStrategy>()
                    // Make sure we always set a strategy so that ExecutionContextBuilder does not create a strategy
                    // which is not compatible with gRPC.
                    .executionStrategy(defaultStrategy());

        ExecutionContextInterceptorHttpServerBuilder(final HttpServerBuilder delegate) {
            super(delegate);
        }

        @Override
        public HttpServerBuilder ioExecutor(final IoExecutor ioExecutor) {
            contextBuilder.ioExecutor(ioExecutor);
            delegate().ioExecutor(ioExecutor);
            return this;
        }

        @Override
        public HttpServerBuilder executor(final Executor executor) {
            contextBuilder.executor(executor);
            delegate().executor(executor);
            return this;
        }

        @Override
        public HttpServerBuilder bufferAllocator(final BufferAllocator allocator) {
            contextBuilder.bufferAllocator(allocator);
            delegate().bufferAllocator(allocator);
            return this;
        }

        @Override
        public HttpServerBuilder executionStrategy(final HttpExecutionStrategy strategy) {
            contextBuilder.executionStrategy(GrpcExecutionStrategy.from(strategy));
            delegate().executionStrategy(strategy);
            return this;
        }
    }
}
