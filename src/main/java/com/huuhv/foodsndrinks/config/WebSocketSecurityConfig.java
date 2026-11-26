package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.service.websocket.StompDestinations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * Phân quyền frame STOMP (kênh inbound) — mặc định từ chối, chỉ mở đúng những gì client cần:
 * <ul>
 *   <li>CONNECT / HEARTBEAT / UNSUBSCRIBE / DISCONNECT: đã đăng nhập (user lấy từ session HTTP lúc handshake).</li>
 *   <li>SUBSCRIBE {@value StompDestinations#ADMIN_ORDERS}: chỉ {@code ADMIN}.</li>
 *   <li>Mọi SEND/MESSAGE: từ chối — simple broker phát thẳng message client gửi vào {@code /topic}/{@code /queue} tới
 *       mọi subscriber (giả mạo sự kiện), và chưa có {@code @MessageMapping} nào dưới {@code /app}. Thêm handler thì
 *       thêm quy tắc tường minh cho đúng đích đó.</li>
 *   <li>SUBSCRIBE đích khác (kể cả {@code /queue/**} trực tiếp — hàng đợi nội bộ của user khác): từ chối.</li>
 * </ul>
 * CSRF: {@code @EnableWebSocketSecurity} bắt frame CONNECT mang token CSRF (header {@code X-CSRF-TOKEN}, token đã mask
 * đọc từ meta {@code _csrf} của trang) khớp token trong session lúc handshake ({@code XorCsrfChannelInterceptor} +
 * {@code CsrfTokenHandshakeInterceptor}) — chặn trang của site khác mở WebSocket bằng cookie của người dùng.
 */
@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    public AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return authorizationRules(messages).build();
    }

    /** Bộ quy tắc, tách riêng để unit test dùng lại mà không cần context Spring. */
    static MessageMatcherDelegatingAuthorizationManager.Builder authorizationRules(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return messages
                .simpTypeMatchers(SimpMessageType.CONNECT, SimpMessageType.HEARTBEAT, SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.DISCONNECT).authenticated()
                .simpSubscribeDestMatchers(StompDestinations.ADMIN_ORDERS).hasRole("ADMIN")
                .anyMessage().denyAll();
    }
}
