package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.repository.HttpSessionStoreRepository;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SpringSessionHttpSessionLookup}: socket chỉ nhớ ID của session HTTP lúc handshake, "còn sống" = kho session dùng
 * chung còn giữ session đó — đúng cả khi session bị huỷ ở instance khác; mọi socket của một chu kỳ được hỏi bằng một
 * query. Câu truy vấn thật trên MySQL: {@code SessionStoreConfigTest}.
 */
class SpringSessionHttpSessionLookupTest {

    /** Kho session giả: id → session (hạn theo {@link MapSession#isExpired}, cùng quy tắc EXPIRY_TIME của kho JDBC). */
    private final Map<String, MapSession> store = new ConcurrentHashMap<>();
    private final HttpSessionStoreRepository storeRepository = mock(HttpSessionStoreRepository.class);
    private final SpringSessionHttpSessionLookup lookup = new SpringSessionHttpSessionLookup(storeRepository);

    @BeforeEach
    void fakeStore() {
        when(storeRepository.findLiveSessionIds(anyCollection(), anyLong())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            return ids.stream().filter(id -> store.containsKey(id) && !store.get(id).isExpired())
                    .collect(Collectors.toSet());
        });
    }
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

        assertThat(lookup.findEnded(List.of(socket))).isEmpty();

        // Logout / AccountStatusFilter ở BẤT KỲ instance nào xoá session khỏi kho
        store.remove("http-1");
        assertThat(lookup.findEnded(List.of(socket))).containsExactly(socket);
    }

    @Test
    void sessionWhoseIdChanged_isNoLongerAlive_underTheOldId() {
        // Đăng nhập lại / tự đổi mật khẩu trong cùng trình duyệt: session mang id mới, id cũ biến mất khỏi kho
        MapSession session = save("http-old", Duration.ofMinutes(30), Instant.now());
        WebSocketSession socket = handshake(new MockHttpSession(null, "http-old"));

        store.remove("http-old");
        session.setId("http-new");
        store.put("http-new", session);

        assertThat(lookup.findEnded(List.of(socket))).containsExactly(socket);
    }

    @Test
    void expiredSession_isNotAlive() {
        // Quá 30 phút không có request HTTP nào — frame WebSocket không gia hạn session
        save("http-idle", Duration.ofMinutes(30), Instant.now().minus(Duration.ofMinutes(31)));
        WebSocketSession socket = handshake(new MockHttpSession(null, "http-idle"));

        assertThat(lookup.findEnded(List.of(socket))).containsExactly(socket);
    }

    @Test
    void socketWithoutRecordedSession_isNotAlive_andTheStoreIsNotAsked() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getAttributes()).thenReturn(new HashMap<>());

        assertThat(lookup.findEnded(List.of(socket))).containsExactly(socket);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void manySockets_areCheckedWithOneStoreQuery() {
        save("http-1", Duration.ofMinutes(30), Instant.now());
        save("http-2", Duration.ofMinutes(30), Instant.now());
        WebSocketSession first = handshake(new MockHttpSession(null, "http-1"));
        WebSocketSession secondTab = handshake(new MockHttpSession(null, "http-1"));
        WebSocketSession other = handshake(new MockHttpSession(null, "http-2"));
        WebSocketSession loggedOut = handshake(new MockHttpSession(null, "http-3"));

        assertThat(lookup.findEnded(List.of(first, secondTab, other, loggedOut))).containsExactly(loggedOut);

        verify(storeRepository, times(1)).findLiveSessionIds(anyCollection(), anyLong());
    }

    private MapSession save(String id, Duration maxInactive, Instant lastAccessed) {
        MapSession session = new MapSession(id);
        session.setMaxInactiveInterval(maxInactive);
        session.setLastAccessedTime(lastAccessed);
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
