package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * {@link HttpSessionLookup} cho session HTTP trong bộ nhớ của container servlet (Tomcat): giữ tham chiếu tới
 * {@link HttpSession} lúc handshake rồi hỏi lại chính đối tượng đó.
 * <p>
 * Session đã {@code invalidate()} (logout, {@code AccountStatusFilter}) → mọi getter ném {@link IllegalStateException}.
 * Session quá {@code maxInactiveInterval} nhưng Tomcat chưa kịp dọn (tiến trình nền chạy khoảng mỗi phút) vẫn được coi
 * là hết hạn. Frame WebSocket không gia hạn session HTTP: chỉ request HTTP mới cập nhật {@code lastAccessedTime}.
 * <p>
 * Đúng khi session HTTP và kết nối WebSocket cùng nằm trên một instance (hiện tại load balancer phải sticky theo
 * {@code JSESSIONID}); với session dùng chung giữa các instance thì thay bằng impl tra kho session (xem
 * {@link HttpSessionLookup}).
 */
@Component
public class ServletHttpSessionLookup implements HttpSessionLookup {

    /** Attribute chứa {@link HttpSession} — chỉ impl này đọc. */
    static final String HTTP_SESSION_ATTRIBUTE = ServletHttpSessionLookup.class.getName() + ".SESSION";

    private final Clock clock;

    public ServletHttpSessionLookup() {
        this(Clock.systemUTC());
    }

    ServletHttpSessionLookup(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        HttpSession session = request instanceof ServletServerHttpRequest servletRequest
                ? servletRequest.getServletRequest().getSession(false) : null;
        if (session == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(HTTP_SESSION_ID_ATTRIBUTE, session.getId());
        attributes.put(HTTP_SESSION_ATTRIBUTE, session);
        Instant authenticatedAt = SessionAuthenticationTime.of(session);
        if (authenticatedAt != null) {
            attributes.put(AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // không cần
    }

    @Override
    public boolean isAlive(WebSocketSession webSocketSession) {
        if (!(webSocketSession.getAttributes().get(HTTP_SESSION_ATTRIBUTE) instanceof HttpSession session)) {
            return false;
        }
        try {
            // getLastAccessedTime() ném IllegalStateException nếu session đã bị invalidate
            long lastAccessedTime = session.getLastAccessedTime();
            int maxInactiveSeconds = session.getMaxInactiveInterval();
            return maxInactiveSeconds <= 0 || lastAccessedTime + maxInactiveSeconds * 1000L > clock.millis();
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }
}
