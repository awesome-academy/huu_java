package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SpringSessionHttpSessionLookup}: socket chỉ nhớ ID của session HTTP lúc handshake, "còn sống" = kho session dùng
 * chung còn giữ session đó — đúng cả khi session bị huỷ ở instance khác. Kho ở đây là {@link MapSessionRepository} (cùng
 * hợp đồng {@code SessionRepository} với kho JDBC của app).
 */
class SpringSessionHttpSessionLookupTest {

    private final Map<String, org.springframework.session.Session> store = new ConcurrentHashMap<>();
    private final MapSessionRepository sessionRepository = new MapSessionRepository(store);
    private final SpringSessionHttpSessionLookup lookup = new SpringSessionHttpSessionLookup(sessionRepository);
    private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();

    @Test
    void handshakeWithoutHttpSession_isRejected() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = lookup.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(servletResponse), mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(attributes).isEmpty();
    }

    @Test
    void handshake_recordsOnlyTheSessionIdAndItsAuthenticationTime() {
        Instant authenticatedAt = Instant.parse("2026-11-28T03:00:00.123Z");
        MockHttpSession httpSession = new MockHttpSession(null, "http-1");
        httpSession.setAttribute(SessionAuthenticationTime.ATTRIBUTE, authenticatedAt.toEpochMilli());

        WebSocketSession stamped = handshake(httpSession);
        WebSocketSession unstamped = handshake(new MockHttpSession(null, "http-2"));

        assertThat(HttpSessionLookup.httpSessionId(stamped)).isEqualTo("http-1");
        assertThat(HttpSessionLookup.authenticatedAt(stamped)).isEqualTo(authenticatedAt);
        // Không giữ tham chiếu tới object session của instance: mọi câu hỏi sau này đều đi qua kho dùng chung
        assertThat(stamped.getAttributes()).containsOnlyKeys(
                HttpSessionLookup.HTTP_SESSION_ID_ATTRIBUTE, HttpSessionLookup.AUTHENTICATED_AT_ATTRIBUTE);
        // Session không mang mốc xác thực → null (job coi là cũ nếu mật khẩu đã từng đổi)
        assertThat(HttpSessionLookup.authenticatedAt(unstamped)).isNull();
    }

    @Test
    void socketIsAlive_whileTheSharedStoreStillHoldsItsSession() {
        save("http-1", Duration.ofMinutes(30), Instant.now());
        WebSocketSession socket = handshake(new MockHttpSession(null, "http-1"));

        assertThat(lookup.isAlive(socket)).isTrue();

        // Logout / AccountStatusFilter ở BẤT KỲ instance nào xoá session khỏi kho
        sessionRepository.deleteById("http-1");
        assertThat(lookup.isAlive(socket)).isFalse();
    }

    @Test
    void sessionWhoseIdChanged_isNoLongerAlive_underTheOldId() {
        // Đăng nhập lại / tự đổi mật khẩu trong cùng trình duyệt: session mang id mới, id cũ biến mất khỏi kho
        MapSession session = save("http-old", Duration.ofMinutes(30), Instant.now());
        WebSocketSession socket = handshake(new MockHttpSession(null, "http-old"));

        sessionRepository.deleteById("http-old");
        session.setId("http-new");
        sessionRepository.save(session);

        assertThat(lookup.isAlive(socket)).isFalse();
    }

    @Test
    void expiredSession_isNotAlive() {
        // Quá 30 phút không có request HTTP nào — frame WebSocket không gia hạn session
        save("http-idle", Duration.ofMinutes(30), Instant.now().minus(Duration.ofMinutes(31)));

        assertThat(lookup.isAlive(handshake(new MockHttpSession(null, "http-idle")))).isFalse();
    }

    @Test
    void socketWithoutRecordedSession_isNotAlive() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getAttributes()).thenReturn(new HashMap<>());

        assertThat(lookup.isAlive(socket)).isFalse();
    }

    private MapSession save(String id, Duration maxInactive, Instant lastAccessed) {
        MapSession session = new MapSession(id);
        session.setMaxInactiveInterval(maxInactive);
        session.setLastAccessedTime(lastAccessed);
        // Ghi thẳng vào map: MapSessionRepository.findById tự xoá session hết hạn như kho JDBC
        store.put(id, session);
        return session;
    }

    private WebSocketSession handshake(MockHttpSession httpSession) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(httpSession);
        Map<String, Object> attributes = new HashMap<>();
        assertThat(lookup.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(servletResponse), mock(WebSocketHandler.class), attributes)).isTrue();
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getAttributes()).thenReturn(attributes);
        return socket;
    }
}
