package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.config.properties.LoginAttemptProperties;
import com.huuhv.foodsndrinks.exception.LoginThrottledException;
import com.huuhv.foodsndrinks.exception.PasswordChangeThrottledException;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.utils.RequestUtils;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chống brute-force đăng nhập (form + API) và dò mật khẩu hiện tại khi đổi mật khẩu ({@link LoginAttemptProperties}).
 * <p>
 * Một lần đăng nhập được đếm ở 3 phạm vi, vượt ngưỡng phạm vi nào thì khoá phạm vi đó:
 * <ul>
 *   <li><b>{@code IP_USER}</b> — cặp (IP, tên đăng nhập), ngưỡng thấp: người dùng thật gõ sai chỉ khoá chính mình;
 *       kẻ tấn công không khoá được tài khoản của người khác từ một IP.</li>
 *   <li><b>{@code IP}</b> — mọi tên đăng nhập từ một IP (IPv6: mạng /64): chặn password spraying.</li>
 *   <li><b>{@code ACCOUNT}</b> — một tài khoản từ mọi IP, ngưỡng cao: chặn tấn công phân tán. Được đếm theo cả chuỗi
 *       người dùng nhập (tên không tồn tại bị khoá y hệt, không lộ tài khoản nào có thật) lẫn username thật sau khi tra
 *       DB ({@link #addAccount}) — username và email của cùng tài khoản dùng chung một bộ đếm.</li>
 * </ul>
 * Key tên đăng nhập được chuẩn hoá giống cách MySQL so sánh (collation mặc định {@code utf8mb4_0900_ai_ci} không phân
 * biệt hoa/thường, dấu, ký tự fullwidth): NFKD, bỏ dấu, viết thường — {@code ádmin}, {@code ＡＤＭＩＮ} đều đếm chung với
 * {@code admin}. Trường hợp collation còn coi là bằng nhau mà cách chuẩn hoá này bỏ sót được bộ đếm theo username thật
 * bắt lại.
 * <p>
 * Bộ đếm nằm trong DB (bảng {@code attempt_limits}, xem {@link AttemptLimiter}): mọi instance sau load balancer dùng
 * chung một bộ đếm, restart/deploy không xoá khoá.
 */
@Service
public class LoginAttemptService {

    static final String SCOPE_IP = "IP";
    static final String SCOPE_IP_USER = "IP_USER";
    static final String SCOPE_ACCOUNT = "ACCOUNT";
    static final String SCOPE_PASSWORD_CHANGE = "PASSWORD_CHANGE";

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final AuditLogService auditLogService;
    private final AttemptLimiter ipLimiter;
    private final AttemptLimiter ipUserLimiter;
    private final AttemptLimiter accountLimiter;
    private final AttemptLimiter passwordChangeLimiter;

    public LoginAttemptService(LoginAttemptProperties properties, AttemptLimiterFactory limiters,
                               AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        this.ipLimiter = limiters.create(SCOPE_IP, properties.maxFailuresPerIp(),
                properties.window(), properties.lockDuration());
        this.ipUserLimiter = limiters.create(SCOPE_IP_USER, properties.maxFailures(),
                properties.window(), properties.lockDuration());
        this.accountLimiter = limiters.create(SCOPE_ACCOUNT, properties.maxFailuresPerAccount(),
                properties.window(), properties.lockDuration());
        this.passwordChangeLimiter = limiters.create(SCOPE_PASSWORD_CHANGE, properties.maxFailures(),
                properties.window(), properties.lockDuration());
    }

    // -------------------------------------------------------
    // Đăng nhập username/mật khẩu
    // -------------------------------------------------------

    /**
     * Bắt đầu một lần đăng nhập: kiểm tra khoá ở mọi phạm vi rồi giữ chỗ một lần thử (trước khi so mật khẩu, để request
     * song song không cùng lọt qua). Phải kết thúc bằng đúng một trong {@link #succeeded}, {@link #failed},
     * {@link #abandoned}.
     *
     * @param clientIp IP client ({@code getRemoteAddr()}, đã qua RemoteIpValve ở prod)
     * @throws LoginThrottledException nếu một phạm vi đang bị khoá hoặc đã đủ số lần thử đang chạy
     */
    public LoginAttempt begin(String loginId, String clientIp) {
        String ipKey = RequestUtils.rateLimitKey(clientIp);
        String loginKey = normalize(loginId);
        LoginAttempt attempt = new LoginAttempt(loginId);
        attempt.add(SCOPE_IP, ipLimiter, ipKey);
        attempt.add(SCOPE_IP_USER, ipUserLimiter, ipKey + '|' + loginKey);
        attempt.add(SCOPE_ACCOUNT, accountLimiter, loginKey);
        acquire(attempt, attempt.slots);
        return attempt;
    }

    /**
     * Đã tra được tài khoản: đếm thêm theo username thật (nếu khác chuỗi người dùng nhập, vd. đăng nhập bằng email).
     *
     * @throws LoginThrottledException nếu tài khoản đang bị khoá
     */
    public void addAccount(LoginAttempt attempt, String username) {
        String accountKey = normalize(username);
        if (attempt.slots.stream().anyMatch(slot -> slot.limiter == accountLimiter && slot.key.equals(accountKey))) {
            return;
        }
        Slot slot = new Slot(SCOPE_ACCOUNT, accountLimiter, accountKey, username);
        acquire(attempt, List.of(slot));
        attempt.slots.add(slot);
    }

    /** Sai mật khẩu (hoặc tên không tồn tại): giữ lần đếm ở mọi phạm vi, phạm vi nào đủ ngưỡng thì khoá + ghi audit. */
    public void failed(LoginAttempt attempt) {
        for (Slot slot : attempt.slots) {
            int failures = slot.limiter.recordFailure(slot.key);
            if (failures == 0) {
                continue;
            }
            if (slot.limiter == ipLimiter) {
                auditLogService.loginSourceBlocked(failures, slot.limiter.lockDuration());
            } else {
                String username = slot.username != null ? slot.username : attempt.loginId;
                auditLogService.loginLockedOut(username, slot.scope, failures, slot.limiter.lockDuration());
            }
        }
    }

    /**
     * Đúng mật khẩu: xoá bộ đếm của tên đăng nhập/tài khoản. Bộ đếm theo IP chỉ trả lại lần vừa giữ chỗ — nếu xoá hẳn,
     * kẻ tấn công xen kẽ đăng nhập đúng vào tài khoản của chính mình để reset giới hạn của IP.
     */
    public void succeeded(LoginAttempt attempt) {
        for (Slot slot : attempt.slots) {
            if (slot.limiter == ipLimiter) {
                slot.limiter.release(slot.key);
            } else {
                slot.limiter.reset(slot.key);
            }
        }
    }

    /** Lần thử kết thúc vì lý do khác sai mật khẩu (vd. tài khoản bị khoá, lỗi DB): trả lại các lần đã giữ chỗ. */
    public void abandoned(LoginAttempt attempt) {
        for (Slot slot : attempt.slots) {
            slot.limiter.release(slot.key);
        }
    }

    private static void acquire(LoginAttempt attempt, List<Slot> slots) {
        long retryAfter = 0;
        for (Slot slot : slots) {
            retryAfter = Math.max(retryAfter, slot.limiter.lockedForSeconds(slot.key));
        }
        if (retryAfter > 0) {
            throw new LoginThrottledException(retryAfter);
        }
        List<Slot> acquired = new ArrayList<>();
        for (Slot slot : slots) {
            if (!slot.limiter.tryAcquire(slot.key)) {
                acquired.forEach(done -> done.limiter.release(done.key));
                // Đã đủ số lần thử đang chạy song song (chưa có kết quả): thử lại sau giây lát
                throw new LoginThrottledException(1);
            }
            acquired.add(slot);
        }
    }

    // -------------------------------------------------------
    // Đổi mật khẩu trong hồ sơ: dò "mật khẩu hiện tại" bằng session bị chiếm
    // -------------------------------------------------------

    /**
     * Gọi trước khi so mật khẩu hiện tại; sau đó gọi {@link #passwordChangeFailed} hoặc
     * {@link #passwordChangeSucceeded}.
     *
     * @throws PasswordChangeThrottledException nếu đã sai quá nhiều lần (hoặc quá nhiều lần thử song song)
     */
    public void beginPasswordChange(Long userId) {
        String key = String.valueOf(userId);
        if (passwordChangeLimiter.lockedForSeconds(key) > 0 || !passwordChangeLimiter.tryAcquire(key)) {
            throw new PasswordChangeThrottledException();
        }
    }

    /** @throws PasswordChangeThrottledException nếu lần sai này làm vượt ngưỡng (đã ghi audit) */
    public void passwordChangeFailed(Long userId, String username) {
        int failures = passwordChangeLimiter.recordFailure(String.valueOf(userId));
        if (failures > 0) {
            auditLogService.passwordChangeLockedOut(userId, username, failures, passwordChangeLimiter.lockDuration());
            throw new PasswordChangeThrottledException();
        }
    }

    public void passwordChangeSucceeded(Long userId) {
        passwordChangeLimiter.reset(String.valueOf(userId));
    }

    /** NFKD + bỏ dấu + viết thường, gần với cách collation {@code utf8mb4_0900_ai_ci} so sánh. */
    static String normalize(String loginId) {
        if (loginId == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(loginId.strip(), Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** Trạng thái của một lần đăng nhập đang chạy (các phạm vi đã giữ chỗ). Không dùng chung giữa các thread. */
    public static final class LoginAttempt {

        private final String loginId;
        private final List<Slot> slots = new ArrayList<>(4);

        private LoginAttempt(String loginId) {
            this.loginId = loginId;
        }

        private void add(String scope, AttemptLimiter limiter, String key) {
            slots.add(new Slot(scope, limiter, key, null));
        }
    }

    /** @param username username thật (đã tra DB) để ghi audit; null = dùng chuỗi người dùng nhập */
    private record Slot(String scope, AttemptLimiter limiter, String key, String username) {
    }
}
