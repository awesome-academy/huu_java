package com.huuhv.foodsndrinks.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Giới hạn số lần gửi form đăng ký ({@code POST /register}) từ một IP (IPv6: mạng /64) trong {@code window} (cửa sổ cố
 * định tính từ lần gửi đầu tiên) — trang public, mỗi lần gửi có thể gọi dịch vụ Pwned Passwords bên ngoài.
 */
@Validated
@ConfigurationProperties("app.security.registration")
public record RegistrationRateLimitProperties(
        @DefaultValue("10") @Min(1) int maxPerIp,
        @DefaultValue("1h") Duration window) {
}
