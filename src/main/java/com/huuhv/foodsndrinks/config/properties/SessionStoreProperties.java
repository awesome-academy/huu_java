package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Pool connection RIÊNG của kho session HTTP (Spring Session JDBC) — xem {@code config/SessionStoreConfig}.
 *
 * @param poolSize          số connection tối đa của pool session. Mỗi lần đọc/ghi session chỉ giữ connection vài ms nên
 *                          pool nhỏ là đủ; tính vào tổng connection của instance: (pool chính + pool này) × số instance
 *                          phải nhỏ hơn {@code max_connections} của MySQL
 * @param connectionTimeout chờ lấy connection tối đa — ngắn hơn mặc định 30s của Hikari: kho session quá tải thì request
 *                          lỗi nhanh thay vì giữ thread Tomcat
 */
@Validated
@ConfigurationProperties("app.session")
public record SessionStoreProperties(
        @Min(2) @Max(50) @DefaultValue("10") int poolSize,
        @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 30) @DefaultValue("5s") Duration connectionTimeout) {

    /** Tên pool trong log/metrics của Hikari (pool chính giữ tên mặc định {@code HikariPool-1}). */
    public static final String POOL_NAME = "session-pool";
}
