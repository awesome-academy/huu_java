package com.huuhv.foodsndrinks.service.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Danh sách kết nối WebSocket đang mở trên instance này, để đóng được những kết nối không còn được phép
 * ({@link WebSocketSessionRevalidationJob}) — Spring Security chỉ xác thực lúc handshake, sau đó socket sống tới khi
 * client đóng.
 * <p>
 * Gắn vào transport qua {@link WebSocketHandlerDecoratorFactory} ({@code config/WebSocketConfig}). Mỗi instance chỉ biết
 * socket của chính nó (đúng ý: chỉ instance giữ socket mới đóng được nó), nên job thu hồi chạy trên mọi instance.
 * <p>
 * Đường nhanh: {@link HttpSessionDestroyedEvent} (logout, session hết hạn, {@code AccountStatusFilter} huỷ session — cần
 * bean {@code HttpSessionEventPublisher}) đóng ngay socket gắn với session đó. Event chỉ phát ở instance giữ session và
 * không phát với Spring Session JDBC, nên job vẫn là nguồn chân lý.
 * <p>
 * Đăng nhập lại trong cùng trình duyệt (form/OAuth2, có thể là người khác) giữ nguyên object {@code HttpSession}, chỉ đổi
 * id ({@code ChangeSessionIdAuthenticationStrategy}) → {@link HttpSessionIdChangedEvent}: socket mở trước đó mang danh
 * tính lúc handshake, nên bị đóng; client nối lại bằng danh tính mới của session.
 * <p>
 * Một socket chỉ rời danh sách khi đã thật sự đóng ({@code afterConnectionClosed}): đóng thất bại thì job thử lại ở chu kỳ
 * sau thay vì bỏ sót.
 */
@Slf4j
@Component
public class WebSocketSessionTracker implements WebSocketHandlerDecoratorFactory {

    /** Mã đóng 1008: client không tự kết nối lại được bằng session cũ (handshake bị chuyển hướng tới /login). */
    public static final CloseStatus REVOKED = CloseStatus.POLICY_VIOLATION.withReason("Session revoked");

    private final Map<String, TrackedSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                register(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    void register(WebSocketSession session) {
        // Handshake chỉ qua được khi đã đăng nhập (SecurityConfig), principal là Authentication của session HTTP
        if (session.getPrincipal() instanceof Authentication authentication) {
            sessions.put(session.getId(), new TrackedSession(session, authentication.getName(),
                    List.copyOf(authentication.getAuthorities()), HttpSessionLookup.httpSessionId(session)));
        } else {
            log.warn("WebSocket session without authenticated principal, closing it");
            close(session);
        }
    }

    /** Ảnh chụp các kết nối đang mở. */
    public List<TrackedSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    /**
     * Đóng kết nối. Bản ghi được gỡ ở {@code afterConnectionClosed}; socket đã đóng sẵn thì gỡ ngay. Đóng thất bại (vd.
     * container đang ghi frame khác) → WARN, socket còn trong danh sách để lần kiểm tra sau đóng lại.
     */
    public void close(WebSocketSession session) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        try {
            session.close(REVOKED);
        } catch (IOException | IllegalStateException ex) {
            log.warn("Closing WebSocket session {} failed, will retry: {}", session.getId(), ex.toString());
        }
    }

    @EventListener
    public void onHttpSessionDestroyed(HttpSessionDestroyedEvent event) {
        closeSocketsOf(event.getId(), "an ended HTTP session");
    }

    @EventListener
    public void onHttpSessionIdChanged(HttpSessionIdChangedEvent event) {
        closeSocketsOf(event.getOldSessionId(), "a re-authenticated HTTP session");
    }

    private void closeSocketsOf(String httpSessionId, String reason) {
        int closed = 0;
        for (TrackedSession tracked : snapshot()) {
            if (Objects.equals(tracked.httpSessionId(), httpSessionId)) {
                close(tracked.session());
                closed++;
            }
        }
        if (closed > 0) {
            log.debug("Closed {} WebSocket session(s) of {}", closed, reason);
        }
    }

    /**
     * @param username      {@code Authentication#getName()} lúc handshake (username local với mọi kiểu đăng nhập)
     * @param authorities   quyền lúc handshake — so với DB để phát hiện đổi role
     * @param httpSessionId id session HTTP lúc handshake (không bao giờ ghi ra log)
     */
    public record TrackedSession(WebSocketSession session, String username,
                                 Collection<? extends GrantedAuthority> authorities, String httpSessionId) {
    }
}
