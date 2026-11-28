package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Handshake ghi lại session HTTP; sau đó socket được coi là hết hiệu lực khi session bị huỷ hoặc quá hạn. */
class ServletHttpSessionLookupTest {

    private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();

    @Test
    void handshakeWithoutHttpSession_isRejected() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = new ServletHttpSessionLookup().beforeHandshake(
                new ServletServerHttpRequest(new MockHttpServletRequest()), new ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(attributes).isEmpty();
    }

    @Test
    void liveSession_isAlive_untilInvalidated() {
        MockHttpSession session = new MockHttpSession(null, "http-1");
        ServletHttpSessionLookup lookup = new ServletHttpSessionLookup();
        WebSocketSession webSocket = handshake(lookup, session);

        assertThat(HttpSessionLookup.httpSessionId(webSocket)).isEqualTo("http-1");
        assertThat(lookup.isAlive(webSocket)).isTrue();

        session.invalidate();
        assertThat(lookup.isAlive(webSocket)).isFalse();
    }

    @Test
    void handshake_copiesTheSessionAuthenticationTime_soSocketsOfOldLoginsCanBeRevokedAfterPasswordChange() {
        Instant authenticatedAt = Instant.parse("2026-11-28T03:00:00.123Z");
        MockHttpSession stamped = new MockHttpSession(null, "http-3");
        stamped.setAttribute(SessionAuthenticationTime.ATTRIBUTE, authenticatedAt.toEpochMilli());
        ServletHttpSessionLookup lookup = new ServletHttpSessionLookup();

        assertThat(HttpSessionLookup.authenticatedAt(handshake(lookup, stamped))).isEqualTo(authenticatedAt);
        // Session không mang mốc (đăng nhập trước khi có tính năng này) → null, job coi là cũ nếu mật khẩu đã từng đổi
        assertThat(HttpSessionLookup.authenticatedAt(handshake(lookup, new MockHttpSession(null, "http-4")))).isNull();
    }

    @Test
    void sessionIdleLongerThanTimeout_isNotAlive_evenBeforeContainerExpiresIt() {
        MockHttpSession session = new MockHttpSession(null, "http-2");
        session.setMaxInactiveInterval(1800);
        long lastAccess = session.getLastAccessedTime();
        ServletHttpSessionLookup notYet = new ServletHttpSessionLookup(
                Clock.fixed(Instant.ofEpochMilli(lastAccess + 1_799_000), ZoneOffset.UTC));
        ServletHttpSessionLookup expired = new ServletHttpSessionLookup(
                Clock.fixed(Instant.ofEpochMilli(lastAccess + 1_801_000), ZoneOffset.UTC));

        assertThat(notYet.isAlive(handshake(notYet, session))).isTrue();
        assertThat(expired.isAlive(handshake(expired, session))).isFalse();
    }

    @Test
    void socketWithoutRecordedSession_isNotAlive() {
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getAttributes()).thenReturn(new HashMap<>());

        assertThat(new ServletHttpSessionLookup().isAlive(webSocket)).isFalse();
    }

    private WebSocketSession handshake(ServletHttpSessionLookup lookup, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        Map<String, Object> attributes = new HashMap<>();
        assertThat(lookup.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(servletResponse), mock(WebSocketHandler.class), attributes)).isTrue();
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getAttributes()).thenReturn(attributes);
        return webSocket;
    }
}
