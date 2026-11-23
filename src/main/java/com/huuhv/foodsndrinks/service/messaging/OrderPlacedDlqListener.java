package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * Message của topic {@code order.placed} đã hết số lần giao ({@code artemis/broker.properties}) và bị broker chuyển vào
 * {@code DLQ.order-placed}: ghi log ERROR kèm {@code orderId} và subscription (kênh) bị lỗi (OWASP A09), rồi lấy message
 * khỏi DLQ — dấu vết lâu dài là log.
 * <p>
 * Nhận {@link Message} thô, tự chuyển đổi: message có thể vào DLQ chính vì payload không đọc được (alias lạ, JSON hỏng)
 * — chuyển đổi ở tham số method sẽ lỗi lại mãi. Payload không đọc được thì chỉ log định danh (đã làm sạch), độ dài
 * body, thời điểm gửi; không log nội dung.
 * <p>
 * Gửi lại sau khi khắc phục: kênh đã gửi thành công không gửi lần hai ({@code notification_logs}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedDlqListener {

    /** Property Artemis ghi vào message dead-letter: hàng đợi gốc — với topic là tên subscription. */
    static final String ORIGINAL_QUEUE_PROPERTY = "_AMQ_ORIG_QUEUE";

    private final MessageConverter messageConverter;

    @JmsListener(destination = Destinations.ORDER_PLACED_DLQ)
    public void onDeadLetter(Message message) throws JMSException {
        String subscription = LogSanitizer.sanitize(message.getStringProperty(ORIGINAL_QUEUE_PROPERTY));
        Object payload;
        try {
            payload = messageConverter.fromMessage(message);
        } catch (MessageConversionException ex) {
            // Thông điệp đã được AliasOnlyJsonMessageConverter làm sạch (không chứa payload/alias)
            log.error("Unreadable {} message dead-lettered from subscription {}: {} (sent at {}, body length {})",
                    Destinations.ORDER_PLACED, subscription, ex.getMessage(), message.getJMSTimestamp(),
                    bodyLength(message));
            return;
        }
        if (payload instanceof OrderPlacedMessage orderPlaced) {
            log.error("Notification for order #{} dead-lettered from subscription {} after all delivery attempts",
                    orderPlaced.orderId(), subscription);
        } else {
            log.error("Unexpected {} payload dead-lettered from subscription {}", Destinations.ORDER_PLACED, subscription);
        }
    }

    private static String bodyLength(Message message) {
        try {
            return message instanceof TextMessage text && text.getText() != null
                    ? String.valueOf(text.getText().length()) : "?";
        } catch (JMSException ex) {
            return "?";
        }
    }
}
