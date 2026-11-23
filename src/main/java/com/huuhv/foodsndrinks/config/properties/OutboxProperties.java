package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Transactional Outbox ({@code app.outbox.*}, bảng {@code outbox_messages}).
 *
 * @param pollInterval chu kỳ job gửi lại bản ghi PENDING ({@code OutboxRelayJob}, tính từ lúc lần trước chạy xong). Broker
 *                     hoạt động lại thì bản ghi bị kẹt được gửi trong ≤ một chu kỳ
 * @param retryDelay   gửi lỗi thì chờ ít nhất bao lâu mới thử lại; phải ≤ {@code pollInterval} để lần thử sau rơi vào chu
 *                     kỳ kế tiếp của job
 * @param maxAttempts  số lần gửi tối đa (kể cả lần gửi ngay sau commit); hết → {@code FAILED} + log ERROR
 * @param batchSize    số bản ghi tối đa mỗi lần job chạy (mỗi bản ghi một transaction ngắn)
 * @param retention    giữ bản ghi SENT bao lâu rồi job xoá (để tra cứu khi cần)
 */
@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @NotNull @DurationMin(seconds = 1) @DefaultValue("1m") Duration pollInterval,
        @NotNull @DurationMin(millis = 0) @DefaultValue("30s") Duration retryDelay,
        @Min(1) @Max(100) @DefaultValue("5") int maxAttempts,
        @Min(1) @Max(1000) @DefaultValue("100") int batchSize,
        @NotNull @DurationMin(hours = 1) @DefaultValue("7d") Duration retention) {

    public OutboxProperties {
        if (retryDelay != null && pollInterval != null && retryDelay.compareTo(pollInterval) > 0) {
            throw new IllegalArgumentException("app.outbox.retry-delay (" + retryDelay
                    + ") must be <= app.outbox.poll-interval (" + pollInterval + ")");
        }
    }
}
