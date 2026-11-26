package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Kích thước các thread pool của {@link com.huuhv.foodsndrinks.config.AsyncConfig}. Thời gian chờ task khi tắt app không nằm ở đây mà ở
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
     * Tên bean executor chạy truy vấn dashboard ({@code AsyncConfig}). Đặt ở đây để service dùng được trong
     * {@code @Qualifier} mà không import {@code config} ({@code config} đã import {@code service} — ngược lại sẽ thành vòng).
     */
    public static final String DASHBOARD_EXECUTOR = "dashboardExecutor";

    /**
     * Kích thước chung của một pool; ràng buộc khai báo một lần ở đây (Bean Validation áp ràng buộc của interface cho
     * mọi record implement nó). Pool mới thêm vào {@link AsyncProperties} chỉ cần implement interface này.
     */
    public interface PoolSize {

        /** Số thread thường trực. */
        int coreSize();

        /** Số thread tối đa, chỉ tạo thêm khi hàng đợi đã đầy. */
        int maxSize();

        /** Sức chứa hàng đợi; đầy và đã đủ {@link #maxSize()} thì áp chính sách từ chối của executor (AsyncConfig). */
        int queueCapacity();

        @AssertTrue(message = "max-size must be >= core-size")
        default boolean isMaxSizeAtLeastCoreSize() {
            return maxSize() >= coreSize();
        }
    }

    /** Pool chung ({@code notify-*}). */
    public record Pool(
            @DefaultValue("2") @Min(1) int coreSize,
            @DefaultValue("4") @Min(1) int maxSize,
            @DefaultValue("100") @Min(0) int queueCapacity) implements PoolSize {
    }

    /**
     * Mỗi truy vấn chiếm một connection JDBC: {@code maxSize} không được quá nửa pool Hikari, để dashboard không lấy hết
     * connection của request khác — kiểm tra lúc khởi động trong {@link com.huuhv.foodsndrinks.config.AsyncConfig}.
     *
     * @param timeout thời gian chờ tối đa cho toàn bộ truy vấn của một lần tải dashboard
     */
    public record DashboardPool(
            @DefaultValue("4") @Min(1) int coreSize,
            @DefaultValue("4") @Min(1) int maxSize,
            @DefaultValue("50") @Min(0) int queueCapacity,
            @DefaultValue("10s") Duration timeout) implements PoolSize {

        @AssertTrue(message = "timeout must be positive")
        public boolean isTimeoutPositive() {
            return timeout.isPositive();
        }
    }
}
