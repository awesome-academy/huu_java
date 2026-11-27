package com.huuhv.foodsndrinks.security;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Chống DoS bằng hàng loạt SUBSCRIBE: trùng đích/id hoặc quá số subscription mỗi kết nối → từ chối; UNSUBSCRIBE trả chỗ. */
class StompSubscriptionLimitInterceptorTest {

    private final StompSubscriptionLimitInterceptor interceptor = new StompSubscriptionLimitInterceptor(2);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final Map<String, Object> session = new ConcurrentHashMap<>();

    @Test
    void subscriptionsWithinLimit_passThrough() {
        Message<?> subscribe = frame(SimpMessageType.SUBSCRIBE, "sub-0", "/user/queue/orders", session);

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
        assertThat(interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-1", "/topic/admin/orders", session),
                channel)).isNotNull();
    }

    @Test
    void sameDestinationTwice_isRejected_evenWithNewId() {
        interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-0", "/user/queue/orders", session), channel);

        assertThatThrownBy(() -> interceptor.preSend(
                frame(SimpMessageType.SUBSCRIBE, "sub-1", "/user/queue/orders", session), channel))
                .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> interceptor.preSend(
                frame(SimpMessageType.SUBSCRIBE, "sub-0", "/topic/other", session), channel))
                .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void moreThanLimit_isRejected_untilUnsubscribe() {
        interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-0", "/a", session), channel);
        interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-1", "/b", session), channel);

        assertThatThrownBy(() -> interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-2", "/c", session), channel))
                .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Too many");

        interceptor.preSend(frame(SimpMessageType.UNSUBSCRIBE, "sub-0", null, session), channel);
        assertThat(interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-2", "/c", session), channel)).isNotNull();
    }

    @Test
    void limitIsPerConnection() {
        Map<String, Object> otherSession = new ConcurrentHashMap<>();
        interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-0", "/user/queue/orders", session), channel);

        assertThat(interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-0", "/user/queue/orders", otherSession),
                channel)).isNotNull();
    }

    @Test
    void subscribeWithoutIdDestinationOrSession_isRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, null, "/a", session), channel))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-0", null, session), channel))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(SimpMessageType.SUBSCRIBE, "sub-0", "/a", null), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void otherFrames_passThroughUntouched() {
        Message<?> connect = frame(SimpMessageType.CONNECT, null, null, session);
        Message<?> heartbeat = frame(SimpMessageType.HEARTBEAT, null, null, session);

        assertThat(interceptor.preSend(connect, channel)).isSameAs(connect);
        assertThat(interceptor.preSend(heartbeat, channel)).isSameAs(heartbeat);
        assertThat(session).isEmpty();
    }

    @Test
    void nonPositiveLimit_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StompSubscriptionLimitInterceptor(0));
    }

    private static Message<?> frame(SimpMessageType type, String subscriptionId, String destination,
                                    Map<String, Object> sessionAttributes) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId("ws-1");
        if (subscriptionId != null) {
            headers.setSubscriptionId(subscriptionId);
        }
        if (destination != null) {
            headers.setDestination(destination);
        }
        if (sessionAttributes != null) {
            headers.setSessionAttributes(sessionAttributes);
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
