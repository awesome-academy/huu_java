package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.repository.HttpSessionStoreRepository;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link HttpSessionLookup} trên kho session dùng chung (Spring Session JDBC): lúc handshake chỉ ghi lại ID của session
 * HTTP (và mốc xác thực của nó), sau đó hỏi lại kho session bằng id đó — theo lô, một query cho mọi socket của một chu
 * kỳ ({@link HttpSessionStoreRepository}), không {@code SessionRepository#findById} từng socket (nạp và deserialize toàn bộ
 * attribute của mỗi session, N query trên pool 10 connection mà request web cũng đang dùng).
 * <p>
 * Đúng với nhiều instance không sticky: session có thể bị huỷ ở instance khác (logout, {@code AccountStatusFilter}), hết
 * hạn, hoặc đổi id (đăng nhập lại / đổi mật khẩu trong cùng trình duyệt — dòng cũ mang id mới, id cũ không còn) mà không
 * có event nào báo về instance đang giữ socket; kho session JDBC cũng không phát {@code SessionDestroyedEvent}. Vì vậy
 * {@link WebSocketSessionRevalidationJob} là đường DUY NHẤT đóng socket của session đã kết thúc (≤ một chu kỳ).
 * <p>
 * Chỉ đọc cột {@code SESSION_ID}/{@code EXPIRY_TIME}: kiểm tra này — cũng như mọi frame WebSocket — không gia hạn session
 * HTTP.
 */
@Component
@RequiredArgsConstructor
public class SpringSessionHttpSessionLookup implements HttpSessionLookup {

    private final HttpSessionStoreRepository httpSessionStoreRepository;

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
    public Set<WebSocketSession> findEnded(Collection<WebSocketSession> sessions) {
        Set<WebSocketSession> ended = Collections.newSetFromMap(new IdentityHashMap<>());
        if (sessions.isEmpty()) {
            return ended;
        }
        List<String> httpSessionIds = sessions.stream().map(HttpSessionLookup::httpSessionId)
                .filter(Objects::nonNull).toList();
        Set<String> live = httpSessionIds.isEmpty() ? Set.of()
                : httpSessionStoreRepository.findLiveSessionIds(httpSessionIds, Instant.now().toEpochMilli());
        for (WebSocketSession session : sessions) {
            String httpSessionId = HttpSessionLookup.httpSessionId(session);
            if (httpSessionId == null || !live.contains(httpSessionId)) {
                ended.add(session);
            }
        }
        return ended;
    }
}
