package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.config.properties.WebSocketProperties;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import lombok.RequiredArgsConstructor;
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
import java.util.Set;
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
 * Một socket chỉ rời danh sách khi đã thật sự đóng ({@code afterConnectionClosed}). Socket đã bị thu hồi ({@link #revoke})
 * được đánh dấu {@link TrackedSession#revoked()}: đóng thất bại thì job đóng lại ở chu kỳ sau dù lý do thu hồi không còn
 * kiểm tra lại được (vd. đổi id session — object {@code HttpSession} vẫn sống, username vẫn hợp lệ), và lý do chỉ được
 * audit một lần ({@link #revoke} trả {@code true} đúng lần đầu).
 * <p>
 * Chống DoS: mỗi user tối đa {@code app.websocket.max-connections-per-user} kết nối trên instance này; kết nối vượt (hoặc
 * không có principal đã xác thực) bị đóng ngay và KHÔNG được chuyển cho handler STOMP — không tốn bộ nhớ/thread nào.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionTracker implements WebSocketHandlerDecoratorFactory {

    /** Mã đóng 1008: client không tự kết nối lại được bằng session cũ (handshake bị chuyển hướng tới /login). */
    public static final CloseStatus REVOKED = CloseStatus.POLICY_VIOLATION.withReason("Session revoked");
    /** Mã đóng 1008: user đã mở quá số kết nối cho phép trên instance này. */
    public static final CloseStatus TOO_MANY_CONNECTIONS = CloseStatus.POLICY_VIOLATION.withReason("Too many connections");

    private final WebSocketProperties properties;
    private final Map<String, TrackedSession> sessions = new ConcurrentHashMap<>();
    /** Socket bị từ chối lúc mở: không chuyển tiếp cả sự kiện đóng của nó cho handler STOMP (handler chưa biết socket). */
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (register(session)) {
                    super.afterConnectionEstablished(session);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                if (rejected.remove(session.getId())) {
                    return;
                }
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /**
     * Ghi nhận socket vừa mở. {@code synchronized}: đếm rồi thêm phải nguyên tử, không thì nhiều handshake song song của
     * cùng user vượt được giới hạn.
     *
     * @return {@code false} nếu socket bị từ chối (đã đóng)
     */
    synchronized boolean register(WebSocketSession session) {
        // Handshake chỉ qua được khi đã đăng nhập (SecurityConfig), principal là Authentication của session HTTP
        if (!(session.getPrincipal() instanceof Authentication authentication)) {
            log.warn("WebSocket session without authenticated principal, closing it");
            reject(session, REVOKED);
            return false;
        }
        String username = authentication.getName();
        long open = sessions.values().stream().filter(tracked -> tracked.username().equals(username)).count();
        if (open >= properties.maxConnectionsPerUser()) {
            log.warn("User {} already has {} open WebSocket connections on this instance, rejecting a new one",
                    LogSanitizer.sanitize(username), open);
            reject(session, TOO_MANY_CONNECTIONS);
            return false;
        }
        sessions.put(session.getId(), new TrackedSession(session, username,
                List.copyOf(authentication.getAuthorities()), HttpSessionLookup.httpSessionId(session), false));
        return true;
    }

    private void reject(WebSocketSession session, CloseStatus status) {
        rejected.add(session.getId());
        try {
            session.close(status);
        } catch (IOException | IllegalStateException ex) {
            log.warn("Closing rejected WebSocket session {} failed: {}", session.getId(), ex.toString());
        }
    }

    /** Ảnh chụp các kết nối đang mở. */
    public List<TrackedSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    /**
     * Thu hồi kết nối: đánh dấu {@link TrackedSession#revoked()} rồi đóng (1008). Bản ghi được gỡ ở
     * {@code afterConnectionClosed}; socket đã đóng sẵn thì gỡ ngay. Đóng thất bại (vd. container đang ghi frame khác)
     * → WARN, socket còn trong danh sách với dấu thu hồi để job đóng lại ở chu kỳ sau.
     *
     * @return {@code true} nếu đây là lần đầu socket bị thu hồi — người gọi ghi audit đúng một lần
     */
    public boolean revoke(WebSocketSession session) {
        boolean first = markRevoked(session.getId());
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return first;
        }
        try {
            session.close(REVOKED);
        } catch (IOException | IllegalStateException ex) {
            log.warn("Closing WebSocket session {} failed, will retry: {}", session.getId(), ex.toString());
        }
        return first;
    }

    private boolean markRevoked(String sessionId) {
        boolean[] first = {false};
        sessions.computeIfPresent(sessionId, (id, tracked) -> {
            if (tracked.revoked()) {
                return tracked;
            }
            first[0] = true;
            return tracked.asRevoked();
        });
        return first[0];
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
                revoke(tracked.session());
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
     * @param revoked       đã bị thu hồi nhưng chưa đóng được — chỉ còn việc đóng lại, không kiểm tra/audit lại
     */
    public record TrackedSession(WebSocketSession session, String username,
                                 Collection<? extends GrantedAuthority> authorities, String httpSessionId,
                                 boolean revoked) {

        TrackedSession asRevoked() {
            return new TrackedSession(session, username, authorities, httpSessionId, true);
        }
    }
}
