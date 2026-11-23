package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.service.notification.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Email "Đơn hàng mới" cho admin — shared durable subscription riêng của topic {@code order.placed}: lỗi ở đây chỉ làm
 * subscription này nhận lại message, Slack không bị gửi lại (và ngược lại).
 */
@Component
@RequiredArgsConstructor
public class OrderPlacedEmailConsumer {

    private final OrderPlacedNotificationHandler handler;
    private final EmailNotificationService emailNotificationService;

    @JmsListener(destination = Destinations.ORDER_PLACED,
            subscription = Destinations.ORDER_PLACED_EMAIL_SUBSCRIPTION,
            containerFactory = JmsBrokerProperties.TOPIC_LISTENER_FACTORY)
    public void onOrderPlaced(OrderPlacedMessage message,
                              @Header(name = "JMSXDeliveryCount", required = false) Integer deliveryCount) {
        handler.handle(NotificationChannel.EMAIL, message, deliveryCount,
                emailNotificationService::isEnabled, emailNotificationService::sendNewOrderEmail);
    }
}
