package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageFormatException;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * Message của topic {@code order.placed} đã hết số lần giao ({@code artemis/broker.properties}) và bị broker chuyển vào
 * {@code DLQ.order-placed}: ghi log ERROR kèm {@code orderId} và subscription (kênh) bị lỗi (OWASP A09), đánh dấu bản ghi
 * outbox của message ({@link MessageTypes#OUTBOX_ID_PROPERTY}) là {@code DEAD_LETTERED} — job dọn dẹp không xoá, còn gửi
 * lại được bao lâu cũng được — rồi lấy message khỏi DLQ. Ghi DB lỗi → exception → broker giao lại message DLQ sau.
 * <p>
 * Nhận {@link Message} thô, tự chuyển đổi: message có thể vào DLQ chính vì payload không đọc được (alias lạ, JSON hỏng)
 * — chuyển đổi ở tham số method sẽ lỗi lại mãi. Payload không đọc được thì chỉ log định danh (đã làm sạch), độ dài
 * body, thời điểm gửi; không log nội dung.
 * <p>
 * Gửi lại sau khi khắc phục: đặt bản ghi {@code DEAD_LETTERED} về {@code PENDING} (README); kênh đã gửi thành công không
 * gửi lần hai ({@code notification_logs}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedDlqListener {

    /** Property Artemis ghi vào message dead-letter: hàng đợi gốc — với topic là tên subscription. */
    static final String ORIGINAL_QUEUE_PROPERTY = "_AMQ_ORIG_QUEUE";

    private final MessageConverter messageConverter;
    private final OutboxService outboxService;

    // Một thread là đủ, không chiếm thêm chỗ trong ngân sách connection (ConnectionBudgetCheck)
    @JmsListener(destination = Destinations.ORDER_PLACED_DLQ, concurrency = "1")
    public void onDeadLetter(Message message) throws JMSException {
        String subscription = LogSanitizer.sanitize(message.getStringProperty(ORIGINAL_QUEUE_PROPERTY));
        Long outboxId = outboxIdOf(message);
        Object payload;
        try {
            payload = messageConverter.fromMessage(message);
        } catch (MessageConversionException ex) {
            // Thông điệp đã được AliasOnlyJsonMessageConverter làm sạch (không chứa payload/alias)
            log.error("Unreadable {} message dead-lettered from subscription {}: {} (sent at {}, body length {}, "
                            + "outbox {})", Destinations.ORDER_PLACED, subscription, ex.getMessage(),
                    message.getJMSTimestamp(), bodyLength(message), describe(outboxId));
            keepOutboxRecord(outboxId);
            return;
        }
        if (payload instanceof OrderPlacedMessage orderPlaced) {
            log.error("Notification for order #{} dead-lettered from subscription {} after all delivery attempts "
                    + "(outbox {})", orderPlaced.orderId(), subscription, describe(outboxId));
        } else {
            log.error("Unexpected {} payload dead-lettered from subscription {} (outbox {})", Destinations.ORDER_PLACED,
                    subscription, describe(outboxId));
        }
        keepOutboxRecord(outboxId);
    }

    private void keepOutboxRecord(Long outboxId) {
        if (outboxId != null && !outboxService.markDeadLettered(outboxId)) {
            // Bản ghi đã DEAD_LETTERED (subscription khác đẩy cùng message vào DLQ trước) tính là thành công, không vào đây
            log.warn("Outbox message #{} is no longer SENT (reset to PENDING or purged), left unchanged", outboxId);
        }
    }

    private static String describe(Long outboxId) {
        return outboxId == null ? "none" : "#" + outboxId;
    }

    /** {@code null} nếu message không đi qua outbox (gửi thẳng) hoặc property không hợp lệ. */
    private static Long outboxIdOf(Message message) throws JMSException {
        if (!message.propertyExists(MessageTypes.OUTBOX_ID_PROPERTY)) {
            return null;
        }
        try {
            long id = message.getLongProperty(MessageTypes.OUTBOX_ID_PROPERTY);
            return id > 0 ? id : null;
        } catch (MessageFormatException ex) {
            return null;
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
