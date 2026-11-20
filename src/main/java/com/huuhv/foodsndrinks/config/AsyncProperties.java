package com.huuhv.foodsndrinks.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Kích thước các thread pool của {@link AsyncConfig}. Thời gian chờ task khi tắt app không nằm ở đây mà ở
 * {@code spring.task.execution.shutdown.*} — {@code ThreadPoolTaskExecutorBuilder} áp cho mọi executor.
 *
 * @param notification pool {@code notify-*} gửi Slack/email (executor mặc định của {@code @Async})
 * @param dashboard    pool {@code dashboard-*} chạy song song các truy vấn thống kê của dashboard admin
 */
@Validated
@ConfigurationProperties("app.async")
public record AsyncProperties(
        @DefaultValue @Valid Pool notification,
        @DefaultValue @Valid DashboardPool dashboard) {

    /**
     * @param coreSize      số thread thường trực
     * @param maxSize       số thread tối đa, chỉ tạo thêm khi hàng đợi đã đầy
     * @param queueCapacity sức chứa hàng đợi; đầy và đã đủ {@code maxSize} thì task chạy luôn trên thread gọi
     *                      ({@code CallerRunsPolicy}) thay vì bị từ chối
     */
    public record Pool(
            @DefaultValue("2") @Min(1) int coreSize,
            @DefaultValue("4") @Min(1) int maxSize,
            @DefaultValue("100") @Min(0) int queueCapacity) {

        @AssertTrue(message = "max-size must be >= core-size")
        public boolean isMaxSizeAtLeastCoreSize() {
            return maxSize >= coreSize;
        }
    }

    /**
     * Mỗi truy vấn chiếm một connection JDBC: {@code maxSize} phải nhỏ hơn hẳn pool Hikari (mặc định 10) để dashboard
     * không lấy hết connection của request khác.
     *
     * @param timeout thời gian chờ tối đa cho toàn bộ truy vấn của một lần tải dashboard
     */
    public record DashboardPool(
            @DefaultValue("4") @Min(1) int coreSize,
            @DefaultValue("4") @Min(1) int maxSize,
            @DefaultValue("50") @Min(0) int queueCapacity,
            @DefaultValue("10s") Duration timeout) {

        @AssertTrue(message = "max-size must be >= core-size")
        public boolean isMaxSizeAtLeastCoreSize() {
            return maxSize >= coreSize;
        }

        @AssertTrue(message = "timeout must be positive")
        public boolean isTimeoutPositive() {
            return timeout.isPositive();
        }
    }
}
