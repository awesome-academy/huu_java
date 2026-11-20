package com.huuhv.foodsndrinks.service.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.huuhv.foodsndrinks.config.RegistrationRateLimitProperties;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.utils.RequestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Giới hạn số lần gửi form đăng ký theo IP ({@link RegistrationRateLimitProperties}); mọi lần gửi đều được đếm, kể cả
 * lần hợp lệ. Bộ đếm trong bộ nhớ JVM — chỉ đúng khi chạy 1 instance (như {@link LoginAttemptService}).
 */
@Service
public class RegistrationRateLimitService {

    private final AuditLogService auditLogService;
    private final AttemptLimiter limiter;

    @Autowired
    public RegistrationRateLimitService(RegistrationRateLimitProperties properties, AuditLogService auditLogService) {
        this(properties, auditLogService, Ticker.systemTicker());
    }

    RegistrationRateLimitService(RegistrationRateLimitProperties properties, AuditLogService auditLogService,
                                 Ticker ticker) {
        this.auditLogService = auditLogService;
        // Không bao giờ ghi nhận "sai" nên không có khoá: chỉ dùng bộ đếm theo cửa sổ
        this.limiter = new AttemptLimiter("REGISTRATION", properties.maxPerIp(), properties.window(),
                properties.window(), ticker);
    }

    /** @return {@code false} (đã ghi audit) nếu IP này đã gửi đủ số lần cho phép trong cửa sổ hiện tại */
    public boolean tryAcquire(String clientIp) {
        if (limiter.tryAcquire(RequestUtils.rateLimitKey(clientIp))) {
            return true;
        }
        auditLogService.registrationThrottled();
        return false;
    }
}
