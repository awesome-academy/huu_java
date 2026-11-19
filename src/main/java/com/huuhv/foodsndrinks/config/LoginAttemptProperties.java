package com.huuhv.foodsndrinks.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Chống brute-force đăng nhập (form + API): sai {@code maxFailures} lần trong {@code window} (tính từ lần sai đầu
 * tiên) với cùng một tên đăng nhập → khoá đăng nhập tên đó trong {@code lockDuration}.
 */
@Validated
@ConfigurationProperties("app.security.login-attempts")
public record LoginAttemptProperties(
        @DefaultValue("5") @Min(1) int maxFailures,
        @DefaultValue("15m") Duration window,
        @DefaultValue("15m") Duration lockDuration) {
}
