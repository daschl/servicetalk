/*
 * Copyright © 2018, 2020-2022 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.transport.netty.internal;

import io.servicetalk.concurrent.api.internal.SubscribablePublisher;
import io.servicetalk.concurrent.internal.DuplicateSubscribeException;
import io.servicetalk.concurrent.internal.TerminalNotification;

import io.netty.channel.Channel;
import io.netty.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.internal.FlowControlUtils.addWithOverflowProtection;
import static io.servicetalk.concurrent.internal.SubscriberUtils.deliverErrorFromSource;
import static io.servicetalk.concurrent.internal.SubscriberUtils.handleExceptionFromOnSubscribe;
import static io.servicetalk.concurrent.internal.SubscriberUtils.isRequestNValid;
import static io.servicetalk.concurrent.internal.SubscriberUtils.newExceptionForInvalidRequestN;
import static io.servicetalk.concurrent.internal.TerminalNotification.complete;
import static io.servicetalk.concurrent.internal.TerminalNotification.error;
import static io.servicetalk.transport.netty.internal.ChannelCloseUtils.assignConnectionError;
import static io.servicetalk.transport.netty.internal.ChannelCloseUtils.close;

final class NettyChannelPublisher<T> extends SubscribablePublisher<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyChannelPublisher.class);
    private long requestCount;
    private boolean requested;
    @Nullable
    private SubscriptionImpl subscription;
    /**
     * The size of the queue is bound by {@link SubscriptionImpl#request(long)} demand. Using reactive operators to
     * transform data and letting ServiceTalk subscribe will take care of backpressure automatically.
     */
    @Nullable
    private Queue<Object> pending;
    @Nullable
    private Throwable fatalError;

    private final Channel channel;
    private final CloseHandler closeHandler;

    NettyChannelPublisher(final Channel channel, final CloseHandler closeHandler) {
        this.channel = channel;
        this.closeHandler = closeHandler;
    }

    @Override
    protected synchronized void handleSubscribe(final Subscriber<? super T> nextSubscriber) {
        subscribe0(nextSubscriber);
    }

    synchronized void channelOnRead(final T data) {
        if (data instanceof ReferenceCounted) {
            channelReadReferenceCounted((ReferenceCounted) data);
            return;
        }
        if (fatalError != null) {
            return;
        }

        if (subscription == null || shouldBuffer()) {
            addPending(data);
            if (subscription != null) {
                processPending(subscription);
            }
        } else {
            emit(subscription, data);
        }
    }

    /**
     * Signifies all data has been read and {@link Subscriber#onComplete()} should be emitted.
     */
    synchronized void channelOnComplete() {
        if (fatalError != null) {
            return;
        }

        if (subscription == null || hasQueuedSignals()) {
            addPending(complete());
            if (subscription != null) {
                processPending(subscription);
            }
        } else {
            emitComplete(subscription);
        }
    }

    synchronized void channelOnError(final Throwable throwable) {
        if (fatalError == null) {
            // The Throwable is propagated as-is downstream but subsequent subscribers should see a
            // ClosedChannelException (with original Throwable as the cause for context).
            fatalError = throwable instanceof ClosedChannelException ? throwable :
                    StacklessClosedChannelException.newInstance(NettyChannelPublisher.class, "channelOnError")
                    .initCause(throwable);
            channelOnError0(throwable);
        }
    }

    synchronized void channelOnReadComplete() {
        requested = false;
        if (requestCount > 0) {
            requestChannel();
        }
    }

    // no synchronize needed (done in SubscriptionImpl)
    private void requestN(final long n, final SubscriptionImpl forSubscription) {
        if (forSubscription != subscription) {
            // Subscription shares common state hence a requestN after termination/cancellation must be ignored
            return;
        }
        if (isRequestNValid(n)) {
            requestCount = addWithOverflowProtection(requestCount, n);
            if (!processPending(forSubscription) && !requested && requestCount > 0) {
                // If subscriber wasn't terminated from the queue, then request more.
                requestChannel();
            }
        } else {
            resetSubscription();
            final IllegalArgumentException cause = newExceptionForInvalidRequestN(n);
            forSubscription.associatedSub.onError(cause);
            // The specification has been violated. There is no way to know if more demand for data will come, so we
            // force close the connection to ensure we don't hang indefinitely.
            close(channel, cause);
        }
    }

    // no synchronize needed (done in SubscriptionImpl)
    private void cancel0(final SubscriptionImpl forSubscription) {
        if (forSubscription != subscription) {
            // Subscription shares common state hence a requestN after termination/cancellation must be ignored
            return;
        }
        LOGGER.debug("{} Cancelling subscription", channel);
        resetSubscription();

        // If a cancel occurs with a valid subscription we need to clear any pending data and set a fatalError so that
        // any future Subscribers don't get partial data delivered from the queue.
        // We don't need to terminate the subscriber because cancellation is originated by the subscriber, pass null.
        emitCatchError(null, StacklessClosedChannelException.newInstance(NettyChannelPublisher.class, "cancel"), true);
    }

    // no synchronize needed (transitively called from synchronized method)
    private void channelOnError0(final Throwable throwable) {
        assignConnectionError(channel, throwable);
        if (subscription == null) {
            closeChannelInbound();
            if (hasQueuedSignals()) {
                addPending(error(throwable));
            }
        } else if (hasQueuedSignals()) {
            addPending(error(throwable));
            processPending(subscription);
        } else {
            emitError(subscription, throwable);
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    private void channelReadReferenceCounted(final ReferenceCounted data) {
        try {
            data.release();
        } finally {
            // We do not expect ref-counted objects here as ST does not support them and do not take care to clean them
            // in error conditions. Hence we fail-fast when we see such objects.
            emitCatchError(subscription,
                    new IllegalArgumentException("Reference counted leaked netty's pipeline. Object: " +
                            data.getClass().getSimpleName()), true);
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    private boolean processPending(SubscriptionImpl target) {
        if (pending == null) {
            return false;
        }

        for (;;) {
            while (requestCount > 0) {
                Object p = pending.poll();
                if (p == null) {
                    return false;
                } else if ((p instanceof TerminalNotification && emit(target, (TerminalNotification) p)) ||
                        emit(target, p)) {
                    // stop draining the pending events if the current Subscription is still the same for which
                    // we started draining, continue emitting the remaining data if there is a new Subscriber
                    if (subscription == null || subscription == target) {
                        tryPreemptiveChannelCloseInbound();
                        return true;
                    }
                    target = subscription;
                }
            }
            if (pending.peek() instanceof TerminalNotification) {
                emit(target, (TerminalNotification) pending.poll());
                // stop draining the pending events if the current Subscription is still the same for which we started
                // draining, continue emitting the remaining data if there is a new Subscriber
                if (subscription == null || subscription == target) {
                    tryPreemptiveChannelCloseInbound();
                    return true;
                }
                target = subscription;
            } else {
                return false;
            }
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    private void tryPreemptiveChannelCloseInbound() {
        assert pending != null;
        final Object top = pending.peek();
        if (top instanceof TerminalNotification) {
            final TerminalNotification terminal = (TerminalNotification) top;
            if (terminal.cause() != null) {
                assert fatalError != null;
                pending.poll();
                closeChannelInbound();
            }
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    private boolean emit(final SubscriptionImpl target, final Object next) {
        assert requestCount > 0;
        --requestCount;
        try {
            @SuppressWarnings("unchecked")
            final T t = (T) next;
            target.associatedSub.onNext(t);
        } catch (Throwable cause) {
            emitCatchError(target, cause, true);
            return true;
        }
        return false;
    }

    // no synchronize needed (transitively called from synchronized method)
    @SuppressWarnings("StatementWithEmptyBody")
    private void emitCatchError(final @Nullable SubscriptionImpl target, final Throwable cause,
                                final boolean drainPendingToNextTerminal) {
        // If we have items queued, we avoid delivering partial content to the next subscriber by draining until we see
        // a Terminal signal. We also don't enqueue future signals after we see a fatal error.
        if (pending != null && drainPendingToNextTerminal) {
            Object top;
            while ((top = pending.poll()) != null && !(top instanceof TerminalNotification)) {
                // intentionally empty
            }
        }
        if (fatalError == null) {
            fatalError = cause;
        }
        if (target != null) {
            emitError(target, cause);
        } else {
            // This branch executes only when an error is originated by the current Subscriber: either an unexpected
            // exception is thrown from Subscriber.onComplete() or cancellation.
            // If an incomplete subscriber is cancelled then close channel. A subscriber can cancel after getting
            // complete, which should not close the channel (won't reach this point, returns earlier).
            // Use outbound/inbound closure instead of channel.close() to register CHANNEL_CLOSED_OUTBOUND event.
            closeChannelOutbound();
            closeChannelInbound();
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    private boolean emit(final SubscriptionImpl target, final TerminalNotification terminal) {
        final Throwable cause = terminal.cause();
        if (cause == null) {
            emitComplete(target);
        } else {
            emitError(target, cause);
        }
        return true;
    }

    // no synchronize needed (transitively called from synchronized method)
    private void emitComplete(final SubscriptionImpl target) {
        resetSubscription();
        try {
            target.associatedSub.onComplete();
        } catch (Throwable cause) {
            LOGGER.debug("Caught unexpected exception from Subscriber {}, closing channel {}",
                    target.associatedSub, channel, cause);
            emitCatchError(null, cause, false);
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    private void emitError(final SubscriptionImpl target, final Throwable throwable) {
        resetSubscription();
        try {
            target.associatedSub.onError(throwable);
        } finally {
            // We do not support resumption once we observe an error since we are not sure whether the channel is in a
            // state to be resumed. Users are responsible to catch-ignore resumable exceptions in the pipeline or from
            // the processing of a message in onNext().
            closeChannelInbound();
        }
    }

    // no synchronize needed (transitively called from synchronized method)
    // For cases when an error occurred in netty pipeline
    private void closeChannelInbound() {
        closeHandler.closeChannelInbound(channel);
    }

    // no synchronize needed (transitively called from synchronized method)
    // For cases with an error occurred in subscriber or a result of cancellation
    private void closeChannelOutbound() {
        closeHandler.closeChannelOutbound(channel);
    }

    // no synchronize needed (transitively called from synchronized method)
    private void resetSubscription() {
        subscription = null;
        requestCount = 0;
    }

    // no synchronize needed (transitively called from synchronized method)
    private void requestChannel() {
        requested = true;
        channel.read();
    }

    // no synchronize needed (transitively called from synchronized method)
    private void addPending(final Object p) {
        if (pending == null) {
            pending = new ArrayDeque<>(4);  // queue should be able to fit: headers + payloadBody + trailers
        }
        pending.add(p);
    }

    // no synchronize needed (transitively called from synchronized method)
    private boolean shouldBuffer() {
        return hasQueuedSignals() || requestCount == 0;
    }

    // no synchronize needed (transitively called from synchronized method)
    private boolean hasQueuedSignals() {
        return pending != null && !pending.isEmpty();
    }

    // no synchronize needed (transitively called from synchronized method)
    private void subscribe0(final Subscriber<? super T> subscriber) {
        SubscriptionImpl subscription = this.subscription;
        if (subscription != null) {
            deliverErrorFromSource(subscriber,
                    new DuplicateSubscribeException(subscription.associatedSub, subscriber));
        } else {
            assert requestCount == 0;
            subscription = new SubscriptionImpl(subscriber);
            this.subscription = subscription;
            try {
                subscriber.onSubscribe(subscription);
            } catch (Throwable t) {
                handleExceptionFromOnSubscribe(subscriber, t);
                resetSubscription();
                emitCatchError(null,
                        StacklessClosedChannelException.newInstance(NettyChannelPublisher.class, "subscribe0")
                                .initCause(t), false);
                return;
            }
            // Fatal error is removed from the queue once it is drained for a Subscriber.
            // In absence of the below, any subsequent Subscriber will not get any fatal error.
            if (subscription == this.subscription && !processPending(subscription) &&
                    (fatalError != null && !hasQueuedSignals())) {
                // We are already on the eventloop, so we are sure that nobody else is emitting to the Subscriber.
                emitError(subscription, fatalError);
            }
        }
    }

    private final class SubscriptionImpl implements Subscription {

        final Subscriber<? super T> associatedSub;

        private SubscriptionImpl(Subscriber<? super T> associatedSub) {
            this.associatedSub = associatedSub;
        }

        @Override
        public void request(long n) {
            synchronized (NettyChannelPublisher.this) {
                NettyChannelPublisher.this.requestN(n, this);
            }
        }

        @Override
        public void cancel() {
            synchronized (NettyChannelPublisher.this) {
                NettyChannelPublisher.this.cancel0(this);
            }
        }
    }
}
