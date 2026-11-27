package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.config.properties.WebSocketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final int MAX_CONNECTIONS = 2;

    private final WebSocketSessionTracker tracker = new WebSocketSessionTracker(properties(MAX_CONNECTIONS));
    private final WebSocketHandler delegate = mock(WebSocketHandler.class);
    private final WebSocketHandler decorated = tracker.decorate(delegate);

    static WebSocketProperties properties(int maxConnectionsPerUser) {
        return new WebSocketProperties(List.of(), Duration.ofSeconds(60), Duration.ofSeconds(10), 5,
                maxConnectionsPerUser);
    }

    @Test
    void connectionsBeyondPerUserLimit_areClosed_withoutReachingStompHandler() throws Exception {
        WebSocketSession tab1 = socket("ws-a1", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        WebSocketSession tab2 = socket("ws-a2", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        WebSocketSession tab3 = socket("ws-a3", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        WebSocketSession bob = socket("ws-b1", "http-b", new TestingAuthenticationToken("bob", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(tab1);
        decorated.afterConnectionEstablished(tab2);

        decorated.afterConnectionEstablished(tab3);
        decorated.afterConnectionEstablished(bob);

        verify(tab3).close(WebSocketSessionTracker.TOO_MANY_CONNECTIONS);
        // Socket bị từ chối không tới handler STOMP — cả lúc mở lẫn lúc đóng
        verify(delegate, never()).afterConnectionEstablished(tab3);
        verify(delegate, never()).afterConnectionClosed(eq(tab3), any());
        // Giới hạn theo từng user
        verify(delegate).afterConnectionEstablished(bob);
        assertThat(tracker.snapshot()).extracting(WebSocketSessionTracker.TrackedSession::username)
                .containsExactlyInAnyOrder("alice", "alice", "bob");

        // Đóng một tab trả lại chỗ
        decorated.afterConnectionClosed(tab1, CloseStatus.NORMAL);
        WebSocketSession tab4 = socket("ws-a4", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(tab4);
        verify(delegate).afterConnectionEstablished(tab4);
    }

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
        verify(delegate, never()).afterConnectionEstablished(socket);
        verify(delegate, never()).afterConnectionClosed(eq(socket), any());
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

        // Lần đầu → true (người gọi audit), lần thử lại → false (không audit trùng)
        assertThat(tracker.revoke(socket)).isTrue();

        assertThat(tracker.snapshot()).singleElement().satisfies(tracked -> {
            assertThat(tracked.username()).isEqualTo("alice");
            assertThat(tracked.revoked()).isTrue();
        });

        assertThat(tracker.revoke(socket)).isFalse();
        verify(socket, times(2)).close(WebSocketSessionTracker.REVOKED);
    }

    @Test
    void failedCloseAfterSessionIdChange_staysMarkedRevoked_soJobCanRetry() throws Exception {
        WebSocketSession socket = socket("ws-10", "http-old", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(socket);
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).when(socket).close(any(CloseStatus.class));

        tracker.onHttpSessionIdChanged(new HttpSessionIdChangedEvent(new MockHttpSession(null, "http-new"), "http-old"));

        // Object HttpSession vẫn sống và username vẫn hợp lệ: chỉ dấu thu hồi cho job biết phải đóng lại
        assertThat(tracker.snapshot()).singleElement()
                .extracting(WebSocketSessionTracker.TrackedSession::revoked).isEqualTo(true);
        verify(delegate, never()).afterConnectionClosed(eq(socket), any());
    }

    @Test
    void revokingAlreadyClosedSocket_isIgnored_andUntracked() throws Exception {
        WebSocketSession socket = socket("ws-6", "http-6", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(socket);
        when(socket.isOpen()).thenReturn(false);

        tracker.revoke(socket);

        verify(socket, never()).close(WebSocketSessionTracker.REVOKED);
        assertThat(tracker.snapshot()).isEmpty();
    }

    @Test
    void concurrentHandshakesOfOneUser_neverExceedLimit() throws Exception {
        List<WebSocketSession> sockets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sockets.add(socket("ws-c" + i, "http-c", new TestingAuthenticationToken("carol", "n/a", "ROLE_USER")));
        }
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> handshakes = new ArrayList<>();
            for (WebSocketSession socket : sockets) {
                handshakes.add(threads.submit(() -> {
                    start.await();
                    decorated.afterConnectionEstablished(socket);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> handshake : handshakes) {
                handshake.get(5, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(tracker.snapshot()).hasSize(MAX_CONNECTIONS);
        verify(delegate, times(MAX_CONNECTIONS)).afterConnectionEstablished(any());
    }

    @Test
    void realtimeSubscriptionRestored_closesSocketsOpenedBefore_soClientsResync() throws Exception {
        WebSocketSession alice = socket("ws-r1", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        WebSocketSession admin = socket("ws-r2", "http-b", new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN"));
        decorated.afterConnectionEstablished(alice);
        decorated.afterConnectionEstablished(admin);

        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));

        // 1012: client nối lại như khi mất kết nối, resync sau SUBSCRIBE — không phải thu hồi
        verify(alice).close(WebSocketSessionTracker.RESYNC);
        verify(admin).close(WebSocketSessionTracker.RESYNC);
        assertThat(tracker.snapshot()).isEmpty();

        // Listener thứ hai tạo lại ngay sau (client chưa kịp nối lại — chờ ≥ 1s): không đóng gì thêm
        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.status-changed"));
        verify(alice, times(1)).close(any(CloseStatus.class));

        // Socket nối lại SAU cả hai lần tạo lại subscription đã resync sau đó → được giữ
        WebSocketSession reconnected = socket("ws-r3", "http-a",
                new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(reconnected);
        verify(reconnected, never()).close(any(CloseStatus.class));
        assertThat(tracker.snapshot()).extracting(tracked -> tracked.session().getId()).containsExactly("ws-r3");
    }

    @Test
    void socketReconnectedBetweenTwoRestores_isClosedAgain_becauseItMayHaveMissedTheSecondTopic() throws Exception {
        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));
        WebSocketSession socket = socket("ws-r4", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(socket);

        // Subscription order.status-changed tạo lại muộn hơn: message của nó trước lúc này có thể đã bị bỏ
        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.status-changed"));

        verify(socket).close(WebSocketSessionTracker.RESYNC);
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
