package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.service.websocket.AdminOrderNotifier;
import com.huuhv.foodsndrinks.service.websocket.OrderStatusNotifier;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin đổi trạng thái đơn → chủ đơn và admin đang online (WebSocket). Subscription <b>non-durable, không shared</b>
 * của topic {@code order.status-changed} ({@link JmsBrokerProperties#TOPIC_BROADCAST_LISTENER_FACTORY}), như
 * {@link OrderPlacedWebSocketConsumer}: mỗi instance nhận một bản và đẩy cho client nối vào chính nó.
 * <p>
 * Gửi cho chủ đơn trước (không đụng DB), rồi mới tới admin (đếm đơn chờ — cần DB): DB lỗi không làm mất thông báo của
 * chủ đơn. Không giao lại/DLQ — lỡ sự kiện thì trang tự tải lại trạng thái khi kết nối lại. Message không hợp lệ → WARN
 * (chỉ tên field) rồi bỏ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangedWebSocketConsumer {

    private final OrderStatusNotifier orderStatusNotifier;
    private final AdminOrderNotifier adminOrderNotifier;
    private final Validator validator;

    @JmsListener(destination = Destinations.ORDER_STATUS_CHANGED,
            containerFactory = JmsBrokerProperties.TOPIC_BROADCAST_LISTENER_FACTORY)
    public void onOrderStatusChanged(OrderStatusChangedMessage message) {
        if (message == null) {
            log.warn("Ignoring empty {} message (websocket)", Destinations.ORDER_STATUS_CHANGED);
            return;
        }
        Set<ConstraintViolation<OrderStatusChangedMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String fields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
            log.warn("Ignoring invalid {} message (websocket): invalid {}", Destinations.ORDER_STATUS_CHANGED, fields);
            return;
        }
        orderStatusNotifier.notifyOwner(message);
        adminOrderNotifier.orderStatusChanged(message);
    }
}
