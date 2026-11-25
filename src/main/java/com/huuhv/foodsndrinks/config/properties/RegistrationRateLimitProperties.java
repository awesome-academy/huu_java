package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Giới hạn số lần gửi form đăng ký ({@code POST /register}) từ một IP (IPv6: mạng /64) trong {@code window} (cửa sổ cố
 * định tính từ lần gửi đầu tiên) — trang public, mỗi lần gửi có thể gọi dịch vụ Pwned Passwords bên ngoài. {@code window}
 * ≥ 1 giây: cửa sổ 0 tắt hẳn giới hạn nên app không khởi động.
 */
@Validated
@ConfigurationProperties("app.security.registration")
public record RegistrationRateLimitProperties(
        @DefaultValue("10") @Min(1) int maxPerIp,
        @DefaultValue("1h") @DurationMin(seconds = 1) Duration window) {
}
