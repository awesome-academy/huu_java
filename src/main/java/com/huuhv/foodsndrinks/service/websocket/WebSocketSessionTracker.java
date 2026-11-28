package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.config.properties.WebSocketProperties;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Danh sách kết nối WebSocket đang mở trên instance này, để đóng được những kết nối không còn được phép
 * ({@link WebSocketSessionRevalidationJob}) — Spring Security chỉ xác thực lúc handshake, sau đó socket sống tới khi
 * client đóng.
 * <p>
 * Gắn vào transport qua {@link WebSocketHandlerDecoratorFactory} ({@code config/WebSocketConfig}). Mỗi instance chỉ biết
 * socket của chính nó (đúng ý: chỉ instance giữ socket mới đóng được nó), nên job thu hồi chạy trên mọi instance.
 * <p>
 * Không có "đường nhanh" theo event của session HTTP: session nằm trong kho dùng chung (Spring Session JDBC), có thể kết
 * thúc ở instance khác và kho JDBC không phát event nào — job hỏi lại kho session ({@link HttpSessionLookup}) mỗi chu kỳ.
 * Logout, session hết hạn, {@code AccountStatusFilter} huỷ session, hay đăng nhập lại trong cùng trình duyệt (session đổi
 * id — socket mở trước đó mang danh tính lúc handshake, id cũ không còn trong kho) đều dẫn tới cùng một kết quả: socket
 * bị đóng trong ≤ một chu kỳ, client nối lại bằng danh tính hiện tại của session.
 * <p>
 * Một socket chỉ rời danh sách khi đã thật sự đóng ({@code afterConnectionClosed}). Socket đã bị thu hồi ({@link #revoke})
 * được đánh dấu {@link TrackedSession#revoked()}: đóng thất bại thì job đóng lại ở chu kỳ sau mà không kiểm tra lại lý do,
 * và lý do chỉ được audit một lần ({@link #revoke} trả {@code true} đúng lần đầu).
 * <p>
 * Chống DoS: mỗi user tối đa {@code app.websocket.max-connections-per-user} kết nối trên instance này; kết nối vượt (hoặc
 * không có principal đã xác thực) bị đóng ngay và KHÔNG được chuyển cho handler STOMP — không tốn bộ nhớ/thread nào.
 * Số kết nối mỗi user giữ trong một map riêng, cập nhật nguyên tử theo từng user ({@code ConcurrentHashMap.compute}):
 * không khoá chung cả instance, không duyệt mọi socket mỗi handshake, và việc đóng socket bị từ chối (I/O, có thể chậm
 * với client không đọc) nằm ngoài mọi khoá.
 * <p>
 * Subscription JMS realtime được tạo lại sau khi mất kết nối broker ({@link RealtimeSubscriptionRestoredEvent}) → đóng
 * mọi socket mở trước đó bằng {@link #RESYNC} (1012): client nối lại và tải lại số liệu, bù sự kiện broker đã bỏ. Việc
 * đóng chạy trên executor riêng (không chặn listener JMS); socket chưa đóng được giữ dấu
 * {@link TrackedSession#resyncPending()} để job đóng lại.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionTracker implements WebSocketHandlerDecoratorFactory {

    /** Mã đóng 1008: client không tự kết nối lại được bằng session cũ (handshake bị chuyển hướng tới /login). */
    public static final CloseStatus REVOKED = CloseStatus.POLICY_VIOLATION.withReason("Session revoked");
    /** Mã đóng 1008: user đã mở quá số kết nối cho phép trên instance này. */
    public static final CloseStatus TOO_MANY_CONNECTIONS = CloseStatus.POLICY_VIOLATION.withReason("Too many connections");
    /** Mã đóng 1012: server có thể đã lỡ sự kiện của client — nối lại để resync. */
    public static final CloseStatus RESYNC = CloseStatus.SERVICE_RESTARTED.withReason("Realtime resync");

    private final WebSocketProperties properties;
    /** Đóng socket ngoài thread listener JMS ({@link #onRealtimeSubscriptionRestored}). */
    @Qualifier(WebSocketProperties.CLOSE_EXECUTOR)
    private final Executor closeExecutor;
    private final Map<String, TrackedSession> sessions = new ConcurrentHashMap<>();
    /** Số socket đang theo dõi của mỗi user (chỉ chứa user có ≥ 1 socket). */
    private final Map<String, Integer> openPerUser = new ConcurrentHashMap<>();
    /** Thứ tự mở socket — xác định socket nào mở trước khi subscription realtime được tạo lại. */
    private final AtomicLong sequence = new AtomicLong();
    /** Socket bị từ chối lúc mở: không chuyển tiếp cả sự kiện đóng của nó cho handler STOMP (handler chưa biết socket). */
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (register(session)) {
                    super.afterConnectionEstablished(session);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                if (rejected.remove(session.getId())) {
                    return;
                }
                untrack(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /**
     * Ghi nhận socket vừa mở, hoặc từ chối và đóng nó.
     *
     * @return {@code false} nếu socket bị từ chối (đã đóng)
     */
    boolean register(WebSocketSession session) {
        CloseStatus rejection = admit(session);
        if (rejection == null) {
            return true;
        }
        // Đóng ngoài mọi khoá: close() ghi frame và gọi afterConnectionClosed đồng bộ
        rejected.add(session.getId());
        try {
            session.close(rejection);
        } catch (IOException | IllegalStateException ex) {
            log.warn("Closing rejected WebSocket session {} failed: {}", session.getId(), ex.toString());
        }
        return false;
    }

    /** @return {@code null} nếu socket được nhận (đã ghi nhận), không thì mã đóng */
    private CloseStatus admit(WebSocketSession session) {
        // Handshake chỉ qua được khi đã đăng nhập (SecurityConfig), principal là Authentication của session HTTP
        if (!(session.getPrincipal() instanceof Authentication authentication)) {
            log.warn("WebSocket session without authenticated principal, closing it");
            return REVOKED;
        }
        String username = authentication.getName();
        int limit = properties.maxConnectionsPerUser();
        boolean[] admitted = {false};
        // Đếm + giữ chỗ nguyên tử theo user: handshake song song của cùng user không vượt được giới hạn
        openPerUser.compute(username, (user, open) -> {
            int current = open == null ? 0 : open;
            if (current >= limit) {
                return open;
            }
            admitted[0] = true;
            return current + 1;
        });
        if (!admitted[0]) {
            log.warn("User {} already has {} open WebSocket connections on this instance, rejecting a new one",
                    LogSanitizer.sanitize(username), limit);
            return TOO_MANY_CONNECTIONS;
        }
        sessions.put(session.getId(), new TrackedSession(session, username,
                List.copyOf(authentication.getAuthorities()), HttpSessionLookup.httpSessionId(session),
                HttpSessionLookup.authenticatedAt(session), false, false, sequence.incrementAndGet()));
        return null;
    }

    /** Gỡ socket và trả chỗ của user — chỉ bên gỡ được bản ghi mới giảm bộ đếm (không giảm hai lần). */
    private void untrack(String sessionId) {
        TrackedSession removed = sessions.remove(sessionId);
        if (removed != null) {
            openPerUser.computeIfPresent(removed.username(), (user, open) -> open <= 1 ? null : open - 1);
        }
    }

    /** Ảnh chụp các kết nối đang mở. */
    public List<TrackedSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    /**
     * Thu hồi kết nối: đánh dấu {@link TrackedSession#revoked()} rồi đóng (1008). Bản ghi được gỡ ở
     * {@code afterConnectionClosed}; socket đã đóng sẵn thì gỡ ngay. Đóng thất bại (vd. container đang ghi frame khác)
     * → WARN, socket còn trong danh sách với dấu thu hồi để job đóng lại ở chu kỳ sau.
     *
     * @return {@code true} nếu đây là lần đầu socket bị thu hồi — người gọi ghi audit đúng một lần
     */
    public boolean revoke(WebSocketSession session) {
        boolean first = markRevoked(session.getId());
        if (!session.isOpen()) {
            untrack(session.getId());
            return first;
        }
        try {
            session.close(REVOKED);
        } catch (IOException | IllegalStateException ex) {
            log.warn("Closing WebSocket session {} failed, will retry: {}", session.getId(), ex.toString());
        }
        return first;
    }

    private boolean markRevoked(String sessionId) {
        boolean[] first = {false};
        sessions.computeIfPresent(sessionId, (id, tracked) -> {
            if (tracked.revoked()) {
                return tracked;
            }
            first[0] = true;
            return tracked.asRevoked();
        });
        return first[0];
    }

    /**
     * Đóng ({@link #RESYNC}) mọi socket mở TRƯỚC lúc subscription được tạo lại — socket mở sau đó đã resync sau thời
     * điểm này. Hai listener realtime thường tạo lại gần như cùng lúc (cùng recovery interval) → lần thứ hai không còn
     * socket cũ (client chờ ≥ 1s mới nối lại); nếu lần thứ hai muộn hơn thì socket nối lại ở giữa bị đóng thêm một lần —
     * đúng, vì nó có thể đã lỡ message của topic thứ hai.
     * <p>
     * Sự kiện phát đồng bộ trên thread listener JMS: ở đây chỉ đánh dấu {@link TrackedSession#resyncPending()} (thao tác
     * trên map, không I/O), còn {@code close()} — có thể chờ tới timeout ghi với client treo mạng — chạy trên
     * {@link WebSocketProperties#CLOSE_EXECUTOR}, mỗi socket một task. Đóng thất bại (vd. container đang ghi frame khác)
     * hoặc executor từ chối → socket giữ dấu, {@link WebSocketSessionRevalidationJob} đóng lại ở chu kỳ sau.
     */
    @EventListener
    public void onRealtimeSubscriptionRestored(RealtimeSubscriptionRestoredEvent event) {
        long restoredAt = sequence.get();
        List<WebSocketSession> marked = new ArrayList<>();
        for (TrackedSession tracked : snapshot()) {
            if (tracked.sequence() <= restoredAt && tracked.session().isOpen()
                    && markResyncPending(tracked.session().getId())) {
                marked.add(tracked.session());
            }
        }
        for (WebSocketSession session : marked) {
            try {
                closeExecutor.execute(() -> closeForResync(session));
            } catch (RejectedExecutionException ex) {
                log.warn("Closing WebSocket session {} for resync was rejected, will retry: {}", session.getId(),
                        ex.toString());
            }
        }
        log.info("Realtime subscription to {} re-established, closing {} WebSocket connection(s) so clients resync",
                LogSanitizer.sanitize(event.destination()), marked.size());
    }

    /**
     * Đóng ({@link #RESYNC}) socket đang chờ resync. Bỏ qua nếu socket không còn được theo dõi, không chờ resync hoặc đã
     * bị thu hồi ({@link #revoke} đóng nó bằng {@link #REVOKED}). Đóng thất bại → WARN, giữ dấu để job thử lại.
     */
    public void closeForResync(WebSocketSession session) {
        TrackedSession tracked = sessions.get(session.getId());
        if (tracked == null || !tracked.resyncPending() || tracked.revoked()) {
            return;
        }
        if (!session.isOpen()) {
            untrack(session.getId());
            return;
        }
        try {
            session.close(RESYNC);
        } catch (IOException | IllegalStateException ex) {
            log.warn("Closing WebSocket session {} for resync failed, will retry: {}", session.getId(), ex.toString());
        }
    }

    /** @return {@code true} nếu socket vừa được đánh dấu (chưa bị thu hồi, chưa chờ resync) */
    private boolean markResyncPending(String sessionId) {
        boolean[] marked = {false};
        sessions.computeIfPresent(sessionId, (id, tracked) -> {
            if (tracked.revoked() || tracked.resyncPending()) {
                return tracked;
            }
            marked[0] = true;
            return tracked.asResyncPending();
        });
        return marked[0];
    }

    /**
     * @param username      {@code Authentication#getName()} lúc handshake (username local với mọi kiểu đăng nhập)
     * @param authorities   quyền lúc handshake — so với DB để phát hiện đổi role
     * @param httpSessionId id session HTTP lúc handshake (không bao giờ ghi ra log)
     * @param authenticatedAt mốc xác thực của session HTTP lúc handshake ({@code null} nếu không có) — so với lần đổi
     *                      mật khẩu gần nhất của tài khoản
     * @param revoked       đã bị thu hồi nhưng chưa đóng được — chỉ còn việc đóng lại, không kiểm tra/audit lại
     * @param resyncPending phải đóng ({@link #RESYNC}) để client resync — subscription realtime đã được tạo lại sau khi
     *                      socket mở; còn dấu này nghĩa là chưa đóng được, job đóng lại
     * @param sequence      thứ tự mở socket trên instance này
     */
    public record TrackedSession(WebSocketSession session, String username,
                                 Collection<? extends GrantedAuthority> authorities, String httpSessionId,
                                 Instant authenticatedAt, boolean revoked, boolean resyncPending, long sequence) {

        TrackedSession asRevoked() {
            return new TrackedSession(session, username, authorities, httpSessionId, authenticatedAt, true,
                    resyncPending, sequence);
        }

        TrackedSession asResyncPending() {
            return new TrackedSession(session, username, authorities, httpSessionId, authenticatedAt, revoked, true,
                    sequence);
        }
    }
}
