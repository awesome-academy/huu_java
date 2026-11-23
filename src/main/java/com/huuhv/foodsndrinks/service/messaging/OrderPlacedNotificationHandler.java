package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Xử lý chung của các consumer topic {@code order.placed} (mỗi kênh một subscription): kiểm tra message (Jakarta
 * Validation trên {@link OrderPlacedMessage} — dữ liệu vào từ broker cũng là input, OWASP), bỏ qua kênh đang
 * tắt, gửi tối đa một lần ({@link NotificationLogService}), lỗi thì log kèm {@code orderId} rồi ném lại để broker giao
 * lại — hết số lần ({@code artemis/broker.properties}) thì message vào {@code DLQ.order-placed}
 * ({@link OrderPlacedDlqListener}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OrderPlacedNotificationHandler {

    private final NotificationLogService notificationLogService;
    private final Validator validator;

    void handle(NotificationChannel channel, OrderPlacedMessage message, Integer deliveryCount,
                BooleanSupplier enabled, Consumer<OrderPlacedMessage> sender) {
        // Giao lại cũng không sửa được message không hợp lệ → bỏ (ack), không đẩy vào DLQ
        if (message == null) {
            log.warn("Ignoring empty {} message ({} notification)", Destinations.ORDER_PLACED, channel);
            return;
        }
        Set<ConstraintViolation<OrderPlacedMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            // Chỉ log tên field (từ record của app), không log giá trị bên gửi đặt
            String fields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
            log.warn("Ignoring invalid {} message ({} notification): invalid {}", Destinations.ORDER_PLACED, channel,
                    fields);
            return;
        }
        long orderId = message.orderId();
        if (!enabled.getAsBoolean()) {
            log.warn("{} notification skipped for order #{} (disabled or not configured)", channel, orderId);
            return;
        }
        try {
            if (notificationLogService.sendOnce(orderId, channel, () -> sender.accept(message))) {
                log.info("{} notification sent for order #{}", channel, orderId);
            } else {
                log.info("{} notification for order #{} was already sent, ignoring duplicate delivery", channel, orderId);
            }
        } catch (RuntimeException ex) {
            // Stack trace do JmsListenerErrorHandler ghi; ở đây chỉ thêm ngữ cảnh nghiệp vụ
            log.warn("{} notification for order #{} failed on delivery {}, broker will redeliver (then {}): {}",
                    channel, orderId, deliveryCount == null ? "?" : deliveryCount, Destinations.ORDER_PLACED_DLQ,
                    ex.getMessage());
            throw ex;
        }
    }
}
