package com.huuhv.foodsndrinks.service.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kết nối đang mở được ghi nhận/gỡ theo vòng đời socket; session HTTP kết thúc hoặc đổi id (đăng nhập lại) thì socket của
 * nó bị đóng ngay; đóng thất bại thì vẫn được theo dõi để thử lại.
 */
class WebSocketSessionTrackerTest {

    private final WebSocketSessionTracker tracker = new WebSocketSessionTracker();
    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketHandler decorated = tracker.decorate(delegate);

    @Test
    void openedSession_isTracked_withUserAndHttpSession_untilClosed() throws Exception {
        WebSocketSession socket = socket("ws-1", "http-1", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));

        decorated.afterConnectionEstablished(socket);

        assertThat(tracker.snapshot()).singleElement().satisfies(tracked -> {
            assertThat(tracked.username()).isEqualTo("alice");
            assertThat(tracked.httpSessionId()).isEqualTo("http-1");
            assertThat(tracked.authorities()).extracting("authority").containsExactly("ROLE_USER");
        });
        verify(delegate).afterConnectionEstablished(socket);

        decorated.afterConnectionClosed(socket, CloseStatus.NORMAL);
        assertThat(tracker.snapshot()).isEmpty();
        verify(delegate).afterConnectionClosed(socket, CloseStatus.NORMAL);
    }

    @Test
    void sessionWithoutAuthenticatedPrincipal_isClosed() throws Exception {
        WebSocketSession socket = socket("ws-2", "http-2", null);

        decorated.afterConnectionEstablished(socket);

        assertThat(tracker.snapshot()).isEmpty();
        verify(socket).close(WebSocketSessionTracker.REVOKED);
    }

    @Test
    void destroyedHttpSession_closesOnlyItsSockets() throws Exception {
        WebSocketSession aliceTab1 = socket("ws-3", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        WebSocketSession aliceTab2 = socket("ws-4", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        WebSocketSession bob = socket("ws-5", "http-b", new TestingAuthenticationToken("bob", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(aliceTab1);
        decorated.afterConnectionEstablished(aliceTab2);
        decorated.afterConnectionEstablished(bob);

        tracker.onHttpSessionDestroyed(new HttpSessionDestroyedEvent(new MockHttpSession(null, "http-a")));

        verify(aliceTab1).close(WebSocketSessionTracker.REVOKED);
        verify(aliceTab2).close(WebSocketSessionTracker.REVOKED);
        verify(bob, never()).close(WebSocketSessionTracker.REVOKED);
        assertThat(tracker.snapshot()).extracting(WebSocketSessionTracker.TrackedSession::username).containsExactly("bob");
    }

    @Test
    void httpSessionIdChanged_byLoginInSameBrowser_closesSocketsOfOldId() throws Exception {
        WebSocketSession admin = socket("ws-7", "http-old", new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN"));
        WebSocketSession other = socket("ws-8", "http-other", new TestingAuthenticationToken("bob", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(admin);
        decorated.afterConnectionEstablished(other);

        tracker.onHttpSessionIdChanged(new HttpSessionIdChangedEvent(new MockHttpSession(null, "http-new"), "http-old"));

        verify(admin).close(WebSocketSessionTracker.REVOKED);
        verify(other, never()).close(any(CloseStatus.class));
        assertThat(tracker.snapshot()).extracting(WebSocketSessionTracker.TrackedSession::username).containsExactly("bob");
    }

    @Test
    void failedClose_keepsSocketTracked_soNextCheckRetries() throws Exception {
        WebSocketSession socket = socket("ws-9", "http-9", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(socket);
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).when(socket).close(any(CloseStatus.class));

        tracker.close(socket);

        assertThat(tracker.snapshot()).extracting(WebSocketSessionTracker.TrackedSession::username).containsExactly("alice");

        tracker.close(socket);
        verify(socket, times(2)).close(WebSocketSessionTracker.REVOKED);
    }

    @Test
    void closingAlreadyClosedSocket_isIgnored_andUntracked() throws Exception {
        WebSocketSession socket = socket("ws-6", "http-6", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(socket);
        when(socket.isOpen()).thenReturn(false);

        tracker.close(socket);

        verify(socket, never()).close(WebSocketSessionTracker.REVOKED);
        assertThat(tracker.snapshot()).isEmpty();
    }

    /** Socket giả hành xử như container: {@code close()} thành công → {@code afterConnectionClosed}. */
    private WebSocketSession socket(String id, String httpSessionId, Principal principal) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(HttpSessionLookup.HTTP_SESSION_ID_ATTRIBUTE, httpSessionId);
        when(socket.getId()).thenReturn(id);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getPrincipal()).thenReturn(principal);
        when(socket.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            when(socket.isOpen()).thenReturn(false);
            decorated.afterConnectionClosed(socket, invocation.getArgument(0));
            return null;
        }).when(socket).close(any(CloseStatus.class));
        return socket;
    }
}
