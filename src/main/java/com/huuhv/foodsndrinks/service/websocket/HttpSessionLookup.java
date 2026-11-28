package com.huuhv.foodsndrinks.service.websocket;

import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Điểm thay thế duy nhất cho câu hỏi "session HTTP đã xác thực kết nối WebSocket này còn sống không" — job
 * {@link WebSocketSessionRevalidationJob} đóng socket khi câu trả lời là không (logout, hết hạn, bị huỷ ở instance khác).
 * <p>
 * Là {@link HandshakeInterceptor}: lúc handshake, impl ghi vào attribute của {@link WebSocketSession} thông tin nó cần để
 * trả lời sau đó, và từ chối handshake không có session HTTP (người dùng đã đăng nhập luôn có session — kể cả đăng nhập
 * bằng remember-me). Impl: {@link SpringSessionHttpSessionLookup} — tra kho session dùng chung giữa các instance
 * (Spring Session JDBC); session có thể bị huỷ ở instance khác, nơi không có event nào báo về đây.
 */
public interface HttpSessionLookup extends HandshakeInterceptor {

    /** Attribute (của WebSocket session) chứa id session HTTP lúc handshake — cùng tên Spring dùng. */
    String HTTP_SESSION_ID_ATTRIBUTE = "HTTP.SESSION.ID";

    /**
     * Attribute (của WebSocket session) chứa mốc xác thực ({@link Instant}) của session HTTP lúc handshake
     * ({@code utils.SessionAuthenticationTime}) — impl ghi nếu session có mốc. Job thu hồi so mốc này với lần đổi mật
     * khẩu gần nhất của tài khoản: socket mở từ một session đăng nhập trước khi mật khẩu đổi phải bị đóng.
     */
    String AUTHENTICATED_AT_ATTRIBUTE = "HTTP.SESSION.AUTHENTICATED_AT";

    /**
     * Hỏi kho session MỘT lần cho cả danh sách (job chạy mỗi chu kỳ với mọi socket đang mở của instance).
     *
     * @return các socket trong {@code sessions} mà session HTTP của lúc handshake đã bị huỷ hoặc hết hạn — kể cả socket
     *         không ghi lại id session. So sánh theo identity của {@link WebSocketSession}.
     */
    Set<WebSocketSession> findEnded(Collection<WebSocketSession> sessions);

    /** Mốc xác thực của session HTTP lúc handshake, {@code null} nếu session không mang mốc. */
    static Instant authenticatedAt(WebSocketSession session) {
        return session.getAttributes().get(AUTHENTICATED_AT_ATTRIBUTE) instanceof Instant at ? at : null;
    }

    /** Id session HTTP lúc handshake, {@code null} nếu không có. */
    static String httpSessionId(WebSocketSession session) {
        return session.getAttributes().get(HTTP_SESSION_ID_ATTRIBUTE) instanceof String id ? id : null;
    }
}
