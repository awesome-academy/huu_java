package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Dashboard admin ({@code app.dashboard.*}).
 *
 * @param chartCacheTtl giữ dữ liệu biểu đồ ({@code GET /admin/dashboard/charts}) trong bộ nhớ instance bao lâu
 *                      ({@code service/DashboardChartCache}); bị xoá ngay khi instance nhận sự kiện đơn hàng, nên TTL chỉ
 *                      giới hạn độ trễ của thay đổi KHÔNG có sự kiện (vd. đổi tên sản phẩm). {@code 0s} = tắt cache
 */
@Validated
@ConfigurationProperties("app.dashboard")
public record DashboardProperties(
        @NotNull @DurationMin(seconds = 0) @DurationMax(minutes = 10) @DefaultValue("30s") Duration chartCacheTtl) {
}
