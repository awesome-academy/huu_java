package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.config.properties.WebSocketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
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
import java.util.concurrent.RejectedExecutionException;
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

    /** Executor đóng socket chạy ngay trên thread gọi — test riêng dùng executor giữ task lại. */
    private final WebSocketSessionTracker tracker = new WebSocketSessionTracker(properties(MAX_CONNECTIONS), Runnable::run);
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

    @Test
    void realtimeSubscriptionRestored_closesOnCloseExecutor_notOnTheListenerThread() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        WebSocketSessionTracker asyncTracker = new WebSocketSessionTracker(properties(MAX_CONNECTIONS), queued::add);
        WebSocketHandler asyncDecorated = asyncTracker.decorate(delegate);
        WebSocketSession alice = socket("ws-q1", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        doAnswer(invocation -> {
            when(alice.isOpen()).thenReturn(false);
            asyncDecorated.afterConnectionClosed(alice, invocation.getArgument(0));
            return null;
        }).when(alice).close(any(CloseStatus.class));
        asyncDecorated.afterConnectionEstablished(alice);

        asyncTracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));

        // Thread listener JMS chỉ đánh dấu: client treo mạng không chặn được nó
        verify(alice, never()).close(any(CloseStatus.class));
        assertThat(asyncTracker.snapshot()).singleElement()
                .extracting(WebSocketSessionTracker.TrackedSession::resyncPending).isEqualTo(true);
        assertThat(queued).hasSize(1);

        // Sự kiện thứ hai trong lúc chưa đóng xong: không xếp thêm lần đóng
        asyncTracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.status-changed"));
        assertThat(queued).hasSize(1);

        queued.forEach(Runnable::run);
        verify(alice).close(WebSocketSessionTracker.RESYNC);
        assertThat(asyncTracker.snapshot()).isEmpty();
    }

    @Test
    void failedResyncClose_keepsSocketMarked_soJobClosesItLater() throws Exception {
        WebSocketSession socket = socket("ws-q2", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        decorated.afterConnectionEstablished(socket);
        doThrow(new IllegalStateException("TEXT_PARTIAL_WRITING")).when(socket).close(any(CloseStatus.class));

        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));

        assertThat(tracker.snapshot()).singleElement().satisfies(tracked -> {
            assertThat(tracked.resyncPending()).isTrue();
            assertThat(tracked.revoked()).isFalse();
        });
        tracker.closeForResync(socket);
        verify(socket, times(2)).close(WebSocketSessionTracker.RESYNC);
    }

    @Test
    void rejectedCloseTask_keepsSocketMarked() throws Exception {
        WebSocketSessionTracker rejecting = new WebSocketSessionTracker(properties(MAX_CONNECTIONS), task -> {
            throw new RejectedExecutionException("shutting down");
        });
        WebSocketSession socket = socket("ws-q3", "http-a", new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        rejecting.decorate(delegate).afterConnectionEstablished(socket);

        rejecting.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));

        assertThat(rejecting.snapshot()).singleElement()
                .extracting(WebSocketSessionTracker.TrackedSession::resyncPending).isEqualTo(true);
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
