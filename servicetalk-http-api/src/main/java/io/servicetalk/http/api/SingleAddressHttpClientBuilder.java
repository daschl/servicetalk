/*
 * Copyright © 2018-2023 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.http.api;

import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.client.api.ConnectionFactory;
import io.servicetalk.client.api.ConnectionFactoryFilter;
import io.servicetalk.client.api.LoadBalancer;
import io.servicetalk.client.api.ServiceDiscoverer;
import io.servicetalk.client.api.ServiceDiscovererEvent;
import io.servicetalk.concurrent.api.BiIntFunction;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.context.api.ContextMap;
import io.servicetalk.logging.api.LogLevel;
import io.servicetalk.transport.api.ClientSslConfig;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.ServiceTalkSocketOptions;
import io.servicetalk.transport.api.TransportObserver;

import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A builder of {@link StreamingHttpClient} instances which call a single server based on the provided unresolved
 * address.
 * <p>
 * It also provides a good set of default settings and configurations, which could be used by most users as-is or
 * could be overridden to address specific use cases.
 * @param <U> the type of address before resolution (unresolved address)
 * @param <R> the type of address after resolution (resolved address)
 */
public interface SingleAddressHttpClientBuilder<U, R> extends HttpClientBuilder<U, R, ServiceDiscovererEvent<R>> {
    /**
     * Configure proxy to serve as an intermediary for requests.
     * <p>
     * If the client talks to a proxy over http (not https, {@link #sslConfig(ClientSslConfig) ClientSslConfig} is NOT
     * configured), it will rewrite the request-target to
     * <a href="https://tools.ietf.org/html/rfc7230#section-5.3.2">absolute-form</a>, as specified by the RFC.
     * <p>
     * For secure proxy tunnels (when {@link #sslConfig(ClientSslConfig) ClientSslConfig} is configured) the tunnel is
     * always initialized using
     * <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-9.3.6">HTTP/1.1 CONNECT</a> request. The actual
     * protocol will be negotiated via <a href="https://tools.ietf.org/html/rfc7301">ALPN extension</a> of TLS protocol,
     * taking into account HTTP protocols configured via {@link #protocols(HttpProtocolConfig...)} method.
     *
     * @param proxyAddress Unresolved address of the proxy. When used with a builder created for a resolved address,
     * {@code proxyAddress} should also be already resolved – otherwise runtime exceptions may occur.
     * @return {@code this}.
     */
    default SingleAddressHttpClientBuilder<U, R> proxyAddress(U proxyAddress) { // FIXME: 0.43 - remove default impl
        throw new UnsupportedOperationException("Setting proxy address is not yet supported by "
                + getClass().getName());
    }

    /**
     * Adds a {@link SocketOption} for all connections created by this builder.
     *
     * @param option the option to apply.
     * @param value the value.
     * @param <T> the type of the value.
     * @return {@code this}.
     * @see StandardSocketOptions
     * @see ServiceTalkSocketOptions
     */
    <T> SingleAddressHttpClientBuilder<U, R> socketOption(SocketOption<T> option, T value);

    /**
     * Enables wire-logging for connections created by this builder.
     *
     * @param loggerName The name of the logger to log wire events.
     * @param logLevel The level to log at.
     * @param logUserData {@code true} to include user data (e.g. data, headers, etc.). {@code false} to exclude user
     * data and log only network events. This method is invoked for each data object allowing for dynamic behavior.
     * @return {@code this}.
     */
    SingleAddressHttpClientBuilder<U, R> enableWireLogging(String loggerName,
                                                           LogLevel logLevel, BooleanSupplier logUserData);

    /**
     * Configurations of various HTTP protocol versions.
     * <p>
     * <b>Note:</b> the order of specified protocols will reflect on priorities for
     * <a href="https://tools.ietf.org/html/rfc7301">ALPN</a> in case the connections use TLS.
     *
     * @param protocols {@link HttpProtocolConfig} for each protocol that should be supported.
     * @return {@code this}.
     */
    SingleAddressHttpClientBuilder<U, R> protocols(HttpProtocolConfig... protocols);

    /**
     * Configures automatically setting {@code Host} headers by inferring from the address.
     * <p>
     * When {@code false} is passed, this setting disables the default filter such that no {@code Host} header will be
     * manipulated.
     *
     * @param enable Whether a default filter for inferring the {@code Host} headers should be added.
     * @return {@code this}
     * @see #unresolvedAddressToHost(Function)
     */
    SingleAddressHttpClientBuilder<U, R> hostHeaderFallback(boolean enable);

    /**
     * Provide a hint if response <a href="https://tools.ietf.org/html/rfc7230#section-4.1.2">trailers</a> are allowed
     * to be dropped. This hint maybe ignored if the transport can otherwise infer that
     * <a href="https://tools.ietf.org/html/rfc7230#section-4.1.2">trailers</a> should be preserved. For example, if the
     * response headers contain <a href="https://tools.ietf.org/html/rfc7230#section-4.4">Trailer</a> then this hint
     * maybe ignored.
     * @param allowDrop {@code true} if response
     * <a href="https://tools.ietf.org/html/rfc7230#section-4.1.2">trailers</a> are allowed to be dropped.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> allowDropResponseTrailers(boolean allowDrop);

    /**
     * Appends the filter to the chain of filters used to decorate the {@link StreamingHttpConnection} created by this
     * builder.
     * <p>
     * Filtering allows you to wrap a {@link StreamingHttpConnection} and modify behavior during request/response
     * processing
     * Some potential candidates for filtering include logging, metrics, and decorating responses.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.appendConnectionFilter(filter1).appendConnectionFilter(filter2).appendConnectionFilter(filter3)
     * </pre>
     * making a request to a connection wrapped by this filter chain the order of invocation of these filters will be:
     * <pre>
     *     filter1 ⇒ filter2 ⇒ filter3 ⇒ connection
     * </pre>
     * @param factory {@link StreamingHttpConnectionFilterFactory} to decorate a {@link StreamingHttpConnection} for the
     * purpose of filtering.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> appendConnectionFilter(StreamingHttpConnectionFilterFactory factory);

    /**
     * Appends the filter to the chain of filters used to decorate the {@link StreamingHttpConnection} created by this
     * builder, for every request that passes the provided {@link Predicate}.
     * <p>
     * Filtering allows you to wrap a {@link StreamingHttpConnection} and modify behavior during request/response
     * processing
     * Some potential candidates for filtering include logging, metrics, and decorating responses.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.appendConnectionFilter(filter1).appendConnectionFilter(filter2).appendConnectionFilter(filter3)
     * </pre>
     * making a request to a connection wrapped by this filter chain the order of invocation of these filters will be:
     * <pre>
     *     filter1 ⇒ filter2 ⇒ filter3 ⇒ connection
     * </pre>
     * <p>
     * When overriding this method, delegate to {@code super} as it uses internal utilities to provide a consistent
     * execution flow.
     * @param predicate the {@link Predicate} to test if the filter must be applied.
     * @param factory {@link StreamingHttpConnectionFilterFactory} to decorate a {@link StreamingHttpConnection} for the
     * purpose of filtering.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> appendConnectionFilter(
            Predicate<StreamingHttpRequest> predicate, StreamingHttpConnectionFilterFactory factory);

    @Override
    SingleAddressHttpClientBuilder<U, R> ioExecutor(IoExecutor ioExecutor);

    @Override
    SingleAddressHttpClientBuilder<U, R> executor(Executor executor);

    /**
     * Sets the {@link HttpExecutionStrategy} to be used for client callbacks when executing client requests for all
     * clients created from this builder.
     * <p>
     * Specifying an execution strategy affects the offloading used during execution of client requests:
     * <dl>
     *     <dt>Unspecified or {@link HttpExecutionStrategies#defaultStrategy()}
     *     <dd>Execution of client requests will use a safe (non-blocking) execution strategy appropriate for the
     *     client API used and the filters added. Blocking is always safe as all potentially blocking paths are
     *     offloaded. Each client API variant (async/blocking streaming/aggregate) requires a specific execution
     *     strategy to avoid blocking the event-loop and filters added via
     *     {@link #appendClientFilter(StreamingHttpClientFilterFactory)},
     *     {@link #appendConnectionFilter(StreamingHttpConnectionFilterFactory)}, or
     *     {@link #appendConnectionFactoryFilter(ConnectionFactoryFilter)}, etc. may also require offloading.
     *     The execution strategy for execution of client requests will be computed based on the client API in use and
     *     {@link HttpExecutionStrategyInfluencer#requiredOffloads()} of added the filters.
     *
     *     <dt>{@link HttpExecutionStrategies#offloadNone()}
     *     (or deprecated {@link HttpExecutionStrategies#offloadNever()})
     *     <dd>No offloading will be used during execution of client requests regardless of the client API used or the
     *     influence of added filters. Filters and asynchronous callbacks
     *     <strong style="text-transform: uppercase;">must not</strong> ever block during the execution of client
     *     requests.
     *
     *     <dt>A custom execution strategy ({@link HttpExecutionStrategies#customStrategyBuilder()}) or
     *     {@link HttpExecutionStrategies#offloadAll()}
     *     <dd>The specified execution strategy will be used for executing client requests rather than the client
     *     API's default safe strategy. Like with the default strategy, the actual execution strategy used is computed
     *     from the provided strategy and the execution strategies required by added filters. Filters and asynchronous
     *     callbacks <strong style="text-transform: uppercase;">MAY</strong> only block during the offloaded portions of
     *     the client request execution.
     * </dl>
     * @param strategy {@link HttpExecutionStrategy} to use. If callbacks to the application code may block then those
     * callbacks must request to be offloaded.
     * @return {@code this}.
     * @see HttpExecutionStrategies
     */
    @Override
    SingleAddressHttpClientBuilder<U, R> executionStrategy(HttpExecutionStrategy strategy);

    @Override
    SingleAddressHttpClientBuilder<U, R> bufferAllocator(BufferAllocator allocator);

    /**
     * Appends the filter to the chain of filters used to decorate the {@link ConnectionFactory} used by this
     * builder.
     * <p>
     * Filtering allows you to wrap a {@link ConnectionFactory} and modify behavior of
     * {@link ConnectionFactory#newConnection(Object, ContextMap, TransportObserver)}.
     * Some potential candidates for filtering include logging and metrics.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder
     *         .appendConnectionFactoryFilter(filter1)
     *         .appendConnectionFactoryFilter(filter2)
     *         .appendConnectionFactoryFilter(filter3)
     * </pre>
     * Calling {@link ConnectionFactory} wrapped by this filter chain, the order of invocation of these filters will be:
     * <pre>
     *     filter1 ⇒ filter2 ⇒ filter3 ⇒ original connection factory
     * </pre>
     * @param factory {@link ConnectionFactoryFilter} to use.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> appendConnectionFactoryFilter(
            ConnectionFactoryFilter<R, FilterableStreamingHttpConnection> factory);

    /**
     * Appends the filter to the chain of filters used to decorate the {@link HttpClient} created by this
     * builder.
     * <p>
     * Note this method will be used to decorate the result of {@link #build()} before it is returned to the user.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.appendClientFilter(filter1).appendClientFilter(filter2).appendClientFilter(filter3)
     * </pre>
     * making a request to a client wrapped by this filter chain the order of invocation of these filters will be:
     * <pre>
     *     filter1 ⇒ filter2 ⇒ filter3 ⇒ client
     * </pre>
     *
     * @param factory {@link StreamingHttpClientFilterFactory} to decorate a {@link HttpClient} for the purpose of
     * filtering.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> appendClientFilter(StreamingHttpClientFilterFactory factory);

    /**
     * Appends the filter to the chain of filters used to decorate the {@link HttpClient} created by this
     * builder, for every request that passes the provided {@link Predicate}.
     * <p>
     * Note this method will be used to decorate the result of {@link #build()} before it is
     * returned to the user.
     * <p>
     * The order of execution of these filters are in order of append. If 3 filters are added as follows:
     * <pre>
     *     builder.appendClientFilter(filter1).appendClientFilter(filter2).appendClientFilter(filter3)
     * </pre>
     * making a request to a client wrapped by this filter chain the order of invocation of these filters will be:
     * <pre>
     *     filter1 ⇒ filter2 ⇒ filter3 ⇒ client
     * </pre>
     *
     * @param predicate the {@link Predicate} to test if the filter must be applied.
     * @param factory {@link StreamingHttpClientFilterFactory} to decorate a {@link HttpClient} for the purpose of
     * filtering.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> appendClientFilter(Predicate<StreamingHttpRequest> predicate,
                                                                    StreamingHttpClientFilterFactory factory);

    /**
     * Provides a means to convert {@link U} unresolved address type into a {@link CharSequence}.
     * An example of where this maybe used is to convert the {@link U} to a default host header. It may also
     * be used in the event of proxying.
     * @param unresolvedAddressToHostFunction invoked to convert the {@link U} unresolved address type into a
     * {@link CharSequence} suitable for use in
     * <a href="https://tools.ietf.org/html/rfc7230#section-5.4">Host Header</a> format.
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> unresolvedAddressToHost(
            Function<U, CharSequence> unresolvedAddressToHostFunction);

    /**
     * Sets a {@link ServiceDiscoverer} to resolve addresses of remote servers to connect to.
     *
     * @param serviceDiscoverer The {@link ServiceDiscoverer} to resolve addresses of remote servers to connect to.
     * Lifecycle of the provided {@link ServiceDiscoverer} is managed externally and it should be
     * {@link ServiceDiscoverer#closeAsync() closed} after all built {@link StreamingHttpClient}s will be closed and
     * this {@link ServiceDiscoverer} is no longer needed.
     * @return {@code this}.
     */
    SingleAddressHttpClientBuilder<U, R> serviceDiscoverer(
            ServiceDiscoverer<U, R, ? extends ServiceDiscovererEvent<R>> serviceDiscoverer);

    /**
     * Sets a retry strategy to retry errors emitted by {@link ServiceDiscoverer}.
     * @param retryStrategy a retry strategy to retry errors emitted by {@link ServiceDiscoverer}.
     * @return {@code this}.
     * @see io.servicetalk.concurrent.api.RetryStrategies
     */
    SingleAddressHttpClientBuilder<U, R> retryServiceDiscoveryErrors(
            BiIntFunction<Throwable, ? extends Completable> retryStrategy);

    /**
     * Sets a {@link HttpLoadBalancerFactory} to create {@link LoadBalancer} instances.
     *
     * @param loadBalancerFactory {@link HttpLoadBalancerFactory} to create {@link LoadBalancer} instances.
     * @return {@code this}.
     */
    SingleAddressHttpClientBuilder<U, R> loadBalancerFactory(HttpLoadBalancerFactory<R> loadBalancerFactory);

    /**
     * Set the SSL/TLS configuration.
     * @param sslConfig The configuration to use.
     * @return {@code this}.
     * @see io.servicetalk.transport.api.ClientSslConfigBuilder
     */
    SingleAddressHttpClientBuilder<U, R> sslConfig(ClientSslConfig sslConfig);

    /**
     * Toggle inference of value to use instead of {@link ClientSslConfig#peerHost()}
     * from client's address when peer host is not specified. By default, inference is enabled.
     * @param shouldInfer value indicating whether inference is on ({@code true}) or off ({@code false}).
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> inferPeerHost(boolean shouldInfer);

    /**
     * Toggle inference of value to use instead of {@link ClientSslConfig#peerPort()}
     * from client's address when peer port is not specified (equals {@code -1}). By default, inference is enabled.
     * @param shouldInfer value indicating whether inference is on ({@code true}) or off ({@code false}).
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> inferPeerPort(boolean shouldInfer);

    /**
     * Toggle <a href="https://datatracker.ietf.org/doc/html/rfc6066#section-3">SNI</a>
     * hostname inference from client's address if not explicitly specified
     * via {@link #sslConfig(ClientSslConfig)}. By default, inference is enabled.
     * @param shouldInfer value indicating whether inference is on ({@code true}) or off ({@code false}).
     * @return {@code this}
     */
    SingleAddressHttpClientBuilder<U, R> inferSniHostname(boolean shouldInfer);
}
