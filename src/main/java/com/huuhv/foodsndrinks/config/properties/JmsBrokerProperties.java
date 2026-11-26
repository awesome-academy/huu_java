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

    /*
     * Tên bean JMS khai báo trong config/JmsConfig. Đặt ở đây (không ở JmsConfig) để consumer/producer trong service dùng
     * được mà không import config — JmsConfig đã import service.messaging (MessageTypes), ngược lại sẽ thành vòng.
     */

    /** Listener factory cho topic: {@code @JmsListener(containerFactory = TOPIC_LISTENER_FACTORY, subscription = ...)}. */
    public static final String TOPIC_LISTENER_FACTORY = "topicListenerFactory";
    /**
     * Listener factory cho topic với subscription non-durable, không shared: MỖI instance nhận một bản của mọi message
     * (fan-out sự kiện realtime tới WebSocket của từng instance). Không khai báo {@code subscription}.
     */
    public static final String TOPIC_BROADCAST_LISTENER_FACTORY = "topicBroadcastListenerFactory";
    /** Template gửi lên topic, inject bằng {@code @Qualifier(TOPIC_JMS_TEMPLATE)}. */
    public static final String TOPIC_JMS_TEMPLATE = "topicJmsTemplate";
}
