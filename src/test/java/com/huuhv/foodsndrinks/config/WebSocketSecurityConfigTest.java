package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.service.websocket.StompDestinations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Bộ quy tắc phân quyền frame STOMP — mặc định từ chối, chỉ admin được SUBSCRIBE kênh đơn hàng admin. */
class WebSocketSecurityConfigTest {

    private final AuthorizationManager<Message<?>> rules = WebSocketSecurityConfig
            .authorizationRules(new MessageMatcherDelegatingAuthorizationManager.Builder()).build();

    private static final Authentication ADMIN = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
    private static final Authentication USER = new TestingAuthenticationToken("alice", "n/a", "ROLE_USER");
    private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken("key", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    @Test
    void connectAndHeartbeat_requireAuthentication() {
        for (SimpMessageType type : new SimpMessageType[]{SimpMessageType.CONNECT, SimpMessageType.HEARTBEAT,
                SimpMessageType.UNSUBSCRIBE, SimpMessageType.DISCONNECT}) {
            assertThat(granted(USER, message(type, null))).as(type.name()).isTrue();
            assertThat(granted(ANONYMOUS, message(type, null))).as(type.name()).isFalse();
        }
    }

    @Test
    void adminOrdersTopic_onlyAdminMaySubscribe() {
        Message<?> subscribe = message(SimpMessageType.SUBSCRIBE, StompDestinations.ADMIN_ORDERS);

        assertThat(granted(ADMIN, subscribe)).isTrue();
        assertThat(granted(USER, subscribe)).isFalse();
        assertThat(granted(ANONYMOUS, subscribe)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/admin/orders/extra", "/topic/other", "/queue/orders-user123", "/app/anything",
            "/user/queue/anything"})
    void subscribeToOtherDestinations_isDenied_evenForAdmin(String destination) {
        assertThat(granted(ADMIN, message(SimpMessageType.SUBSCRIBE, destination))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {StompDestinations.ADMIN_ORDERS, "/queue/orders-user123", "/app/anything",
            "/user/admin/queue/orders"})
    void clientSend_isAlwaysDenied(String destination) {
        // simple broker phát thẳng message client gửi vào /topic, /queue tới subscriber → giả mạo sự kiện
        assertThat(granted(ADMIN, message(SimpMessageType.MESSAGE, destination))).isFalse();
    }

    private boolean granted(Authentication authentication, Message<?> message) {
        AuthorizationResult result = rules.authorize(() -> authentication, message);
        return result != null && result.isGranted();
    }

    private static Message<?> message(SimpMessageType type, String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        if (destination != null) {
            headers.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
