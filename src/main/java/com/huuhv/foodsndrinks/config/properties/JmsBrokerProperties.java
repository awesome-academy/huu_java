package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Cấu hình broker JMS của app ({@code app.jms.*}).
 *
 * @param embeddedBrokerProperties file broker properties của Artemis (location kiểu Spring, vd.
 *                                 {@code classpath:artemis/broker.properties}) áp lên broker embedded theo thứ tự —
 *                                 file sau ghi đè khoá trùng của file trước (test thêm file giảm thời gian chờ giao
 *                                 lại). Không có tác dụng với broker riêng ({@code mode=native}): broker đó nạp
 *                                 {@code broker.properties} bằng {@code artemis run --properties}.
 */
@Validated
@ConfigurationProperties("app.jms")
public record JmsBrokerProperties(@NotEmpty List<String> embeddedBrokerProperties) {
}
