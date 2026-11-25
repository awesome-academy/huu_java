package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.config.properties.RegistrationRateLimitProperties;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.utils.RequestUtils;
import org.springframework.stereotype.Service;

/**
 * Giới hạn số lần gửi form đăng ký theo IP ({@link RegistrationRateLimitProperties}); mọi lần gửi đều được đếm, kể cả
 * lần hợp lệ. Bộ đếm dùng chung giữa các instance trong bảng {@code attempt_limits} (như {@link LoginAttemptService}).
 */
@Service
public class RegistrationRateLimitService {

    private final AuditLogService auditLogService;
    private final AttemptLimiter limiter;

    public RegistrationRateLimitService(RegistrationRateLimitProperties properties, AttemptLimiterFactory limiters,
                                        AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        // Không bao giờ ghi nhận "sai" nên không có khoá: chỉ dùng bộ đếm theo cửa sổ
        this.limiter = limiters.create("REGISTRATION", properties.maxPerIp(), properties.window(), properties.window());
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
