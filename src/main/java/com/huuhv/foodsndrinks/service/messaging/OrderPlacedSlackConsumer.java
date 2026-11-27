package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.service.notification.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Thông báo Slack cho đơn mới — shared durable subscription riêng của topic {@code order.placed}: webhook lỗi chỉ làm
 * subscription này nhận lại message, email không bị gửi lại (và ngược lại).
 */
@Component
@RequiredArgsConstructor
public class OrderPlacedSlackConsumer {

    private final OrderPlacedNotificationHandler handler;
    private final SlackNotificationService slackNotificationService;

    @JmsListener(destination = Destinations.ORDER_PLACED,
            subscription = Destinations.ORDER_PLACED_SLACK_SUBSCRIPTION,
            containerFactory = JmsBrokerProperties.TOPIC_LISTENER_FACTORY)
    public void onOrderPlaced(OrderPlacedMessage message,
                              @Header(name = "JMSXDeliveryCount", required = false) Integer deliveryCount) {
        handler.handle(NotificationChannel.SLACK, message, deliveryCount,
                slackNotificationService::isEnabled, slackNotificationService::sendOrderPlaced);
    }
}
