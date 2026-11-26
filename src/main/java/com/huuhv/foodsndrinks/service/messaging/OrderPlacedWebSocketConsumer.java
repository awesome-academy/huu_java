package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.service.websocket.AdminOrderNotifier;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Đơn mới → admin đang online (WebSocket). Subscription <b>non-durable, không shared</b> của topic
 * {@code order.placed} ({@link JmsBrokerProperties#TOPIC_BROADCAST_LISTENER_FACTORY}): mỗi instance nhận một bản và
 * đẩy tới admin nối vào chính nó, nên admin ở instance nào cũng thấy đơn đặt ở instance khác.
 * <p>
 * Khác consumer email/Slack: không chống lặp, không giao lại, không DLQ — lỡ một sự kiện (instance đang khởi động, lỗi
 * DB khi đếm đơn chờ) thì trang admin tự tải lại số liệu khi kết nối lại. Message không hợp lệ (Jakarta Validation, như
 * {@code OrderPlacedNotificationHandler}) → WARN (chỉ tên field) rồi bỏ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedWebSocketConsumer {

    private final AdminOrderNotifier adminOrderNotifier;
    private final Validator validator;

    @JmsListener(destination = Destinations.ORDER_PLACED,
            containerFactory = JmsBrokerProperties.TOPIC_BROADCAST_LISTENER_FACTORY)
    public void onOrderPlaced(OrderPlacedMessage message) {
        if (message == null) {
            log.warn("Ignoring empty {} message (websocket)", Destinations.ORDER_PLACED);
            return;
        }
        Set<ConstraintViolation<OrderPlacedMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String fields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
            log.warn("Ignoring invalid {} message (websocket): invalid {}", Destinations.ORDER_PLACED, fields);
            return;
        }
        adminOrderNotifier.orderPlaced(message);
    }
}
