package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import com.huuhv.foodsndrinks.service.websocket.WebSocketSessionTracker.TrackedSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Đóng các kết nối WebSocket không còn được phép — tương đương {@code AccountStatusFilter} của request HTTP, vì frame
 * STOMP không đi qua filter chain:
 * <ul>
 *   <li>tài khoản bị khoá, bị xoá, đổi role hoặc đổi mật khẩu sau khi session của socket đăng nhập (một query cho mọi username đang kết nối, cùng quy tắc
 *       {@link AccountStatusService#revocationReason}) → audit {@code SESSION_REVOKED};</li>
 *   <li>session HTTP đã kết thúc ở bất kỳ instance nào (logout, hết hạn, bị huỷ, đổi id khi đăng nhập lại) —
 *       {@link HttpSessionLookup} hỏi kho session dùng chung; đây là đường duy nhất, không có event nào báo.</li>
 * </ul>
 * Ngoài ra đóng lại ({@code RESYNC}) socket còn dấu {@link TrackedSession#resyncPending()} — lần đóng sau khi
 * subscription realtime được tạo lại đã thất bại.
 * Trạng thái đọc từ nguồn dùng chung (DB, kho session) nên đúng khi nhiều instance: admin khoá user ở instance A thì
 * instance B đóng socket của user đó trong ≤ {@code app.websocket.revalidate-interval}.
 * <p>
 * Không {@code @SchedulerLock} (ngoại lệ có chủ ý như {@code OutboxRelayJob}): mỗi instance chỉ kiểm tra và đóng socket
 * của chính nó ({@link WebSocketSessionTracker}), khoá chung sẽ làm các instance khác bỏ lượt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionRevalidationJob {

    private final WebSocketSessionTracker tracker;
    private final HttpSessionLookup httpSessionLookup;
    private final AccountStatusService accountStatusService;
    private final AuditLogService auditLogService;

    @Scheduled(initialDelayString = "${app.websocket.revalidate-interval:60s}",
            fixedDelayString = "${app.websocket.revalidate-interval:60s}")
    public void revalidate() {
        try {
            revalidateOpenSessions();
        } catch (RuntimeException ex) {
            // vd. DB tạm lỗi: giữ kết nối, thử lại chu kỳ sau (không đóng hàng loạt vì lỗi hạ tầng)
            log.warn("WebSocket session revalidation failed, will retry next cycle", ex);
        }
    }

    /** @return số kết nối đã đóng */
    int revalidateOpenSessions() {
        List<TrackedSession> sessions = tracker.snapshot();
        if (sessions.isEmpty()) {
            return 0;
        }
        List<String> usernames = sessions.stream().filter(tracked -> !tracked.revoked())
                .map(TrackedSession::username).distinct().toList();
        Map<String, UserAccountStatus> statuses = usernames.isEmpty() ? Map.of()
                : accountStatusService.findAccountStatuses(usernames);
        int closed = 0;
        for (TrackedSession tracked : sessions) {
            // Đã thu hồi mà chưa đóng được (chu kỳ trước, đổi id session...) → chỉ đóng lại: lý do có thể không còn kiểm
            // tra được nữa và đã được audit. Session HTTP kết thúc → đóng, không audit.
            if (tracked.revoked() || !httpSessionLookup.isAlive(tracked.session())) {
                tracker.revoke(tracked.session());
                closed++;
                continue;
            }
            Optional<String> reason = AccountStatusService.revocationReason(
                    statuses.get(tracked.username()), tracked.authorities(), tracked.authenticatedAt());
            if (reason.isPresent()) {
                if (tracker.revoke(tracked.session())) {
                    auditLogService.webSocketSessionRevoked(tracked.username(), reason.get());
                }
                closed++;
            } else if (tracked.resyncPending()) {
                // Subscription realtime đã được tạo lại nhưng chưa đóng được socket này → client chưa resync
                tracker.closeForResync(tracked.session());
                closed++;
            }
        }
        if (closed > 0) {
            log.info("Closed {} of {} WebSocket session(s) that are no longer allowed", closed, sessions.size());
        }
        return closed;
    }
}
