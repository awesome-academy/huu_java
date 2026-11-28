package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;

/**
 * {@link HttpSessionLookup} trên kho session dùng chung (Spring Session JDBC): lúc handshake chỉ ghi lại ID của session
 * HTTP (và mốc xác thực của nó), sau đó hỏi lại kho session bằng id đó.
 * <p>
 * Đúng với nhiều instance không sticky: session có thể bị huỷ ở instance khác (logout, {@code AccountStatusFilter}), hết
 * hạn, hoặc đổi id (đăng nhập lại / đổi mật khẩu trong cùng trình duyệt — dòng cũ mang id mới, id cũ không còn) mà không
 * có event nào báo về instance đang giữ socket; kho session JDBC cũng không phát {@code SessionDestroyedEvent}. Vì vậy
 * {@link WebSocketSessionRevalidationJob} là đường DUY NHẤT đóng socket của session đã kết thúc (≤ một chu kỳ).
 * <p>
 * {@code findById} chỉ đọc (session hết hạn thì bị xoá và trả {@code null}); không {@code save} nên kiểm tra này — cũng như
 * mọi frame WebSocket — không gia hạn session HTTP.
 */
@Component
@RequiredArgsConstructor
public class SpringSessionHttpSessionLookup implements HttpSessionLookup {

    private final SessionRepository<? extends Session> sessionRepository;

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
        String httpSessionId = HttpSessionLookup.httpSessionId(webSocketSession);
        if (httpSessionId == null) {
            return false;
        }
        Session session = sessionRepository.findById(httpSessionId);
        return session != null && !session.isExpired();
    }
}
