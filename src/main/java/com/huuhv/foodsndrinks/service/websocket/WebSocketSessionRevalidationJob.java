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
 *   <li>tài khoản bị khoá, bị xoá hoặc đổi role từ lúc handshake (một query cho mọi username đang kết nối, cùng quy tắc
 *       {@link AccountStatusService#revocationReason}) → audit {@code SESSION_REVOKED};</li>
 *   <li>session HTTP đã kết thúc (logout, hết hạn, bị huỷ) — {@link HttpSessionLookup}.</li>
 * </ul>
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
        Map<String, UserAccountStatus> statuses = accountStatusService.findAccountStatuses(
                sessions.stream().map(TrackedSession::username).toList());
        int closed = 0;
        for (TrackedSession tracked : sessions) {
            if (!httpSessionLookup.isAlive(tracked.session())) {
                tracker.close(tracked.session());
                closed++;
                continue;
            }
            Optional<String> reason = AccountStatusService.revocationReason(
                    statuses.get(tracked.username()), tracked.authorities());
            if (reason.isPresent()) {
                auditLogService.webSocketSessionRevoked(tracked.username(), reason.get());
                tracker.close(tracked.session());
                closed++;
            }
        }
        if (closed > 0) {
            log.info("Closed {} of {} WebSocket session(s) that are no longer allowed", closed, sessions.size());
        }
        return closed;
    }
}
