package com.huuhv.foodsndrinks.service.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.huuhv.foodsndrinks.config.LoginAttemptProperties;
import com.huuhv.foodsndrinks.security.LoginThrottledException;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Đếm số lần đăng nhập sai theo tên đăng nhập và tạm khoá khi vượt ngưỡng ({@link LoginAttemptProperties}).
 * <p>
 * Key là chuỗi người dùng nhập (username hoặc email), viết thường — không tra DB nên tên không tồn tại cũng bị đếm và
 * khoá y hệt, không lộ tài khoản nào có thật. Hệ quả: một tài khoản có 2 key (username và email).
 * <p>
 * <b>Bộ đếm nằm trong bộ nhớ của JVM (Caffeine) → chỉ đúng khi chạy 1 instance.</b> Chạy nhiều instance sau load
 * balancer thì mỗi instance đếm riêng (kẻ tấn công được nhân số lần thử theo số instance) — khi đó cần chuyển bộ
 * đếm sang kho dùng chung (Redis/DB). Số key được giới hạn để kẻ tấn công rải tên ngẫu nhiên không làm đầy bộ nhớ.
 */
@Service
public class LoginAttemptService {

    private static final long MAX_TRACKED_KEYS = 10_000;

    private final LoginAttemptProperties properties;
    private final AuditLogService auditLogService;
    private final Ticker ticker;
    /** Số lần sai trong cửa sổ hiện tại; hết hạn sau {@code window} tính từ lần sai ĐẦU TIÊN (không trượt). */
    private final Cache<String, Integer> failures;
    /** Thời điểm hết khoá (nano giây theo {@link #ticker}). */
    private final Cache<String, Long> locks;

    @Autowired
    public LoginAttemptService(LoginAttemptProperties properties, AuditLogService auditLogService) {
        this(properties, auditLogService, Ticker.systemTicker());
    }

    LoginAttemptService(LoginAttemptProperties properties, AuditLogService auditLogService, Ticker ticker) {
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.ticker = ticker;
        long windowNanos = properties.window().toNanos();
        this.failures = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .ticker(ticker)
                .executor(Runnable::run)
                .expireAfter(new Expiry<String, Integer>() {
                    @Override
                    public long expireAfterCreate(String key, Integer value, long currentTime) {
                        return windowNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, Integer value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, Integer value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
        this.locks = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .ticker(ticker)
                .executor(Runnable::run)
                .expireAfterWrite(properties.lockDuration())
                .build();
    }

    /** @throws LoginThrottledException nếu tên đăng nhập đang bị khoá */
    public void checkNotBlocked(String loginId) {
        Long lockedUntil = locks.getIfPresent(key(loginId));
        if (lockedUntil != null) {
            long remainingNanos = Math.max(0, lockedUntil - ticker.read());
            throw new LoginThrottledException(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remainingNanos + 999_999_999)));
        }
    }

    /** Sai mật khẩu (hoặc tên không tồn tại): tăng bộ đếm, đủ ngưỡng thì khoá và ghi audit. */
    public void loginFailed(String loginId) {
        String key = key(loginId);
        int count = failures.asMap().merge(key, 1, Integer::sum);
        if (count >= properties.maxFailures()) {
            locks.put(key, ticker.read() + properties.lockDuration().toNanos());
            failures.invalidate(key);
            auditLogService.loginLockedOut(loginId, count, properties.lockDuration());
        }
    }

    public void loginSucceeded(String loginId) {
        failures.invalidate(key(loginId));
    }

    private static String key(String loginId) {
        return loginId == null ? "" : loginId.strip().toLowerCase(Locale.ROOT);
    }
}
