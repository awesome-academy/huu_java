package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
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
 * body, thời điểm gửi; không log nội dung. Mọi lỗi khi đọc message (kể cả {@code JMSException} vì property sai kiểu)
 * đều đi nhánh này, không ném ra: DLQ thử lại không giới hạn nên ném vì lỗi của chính message là lặp vô hạn. Chỉ lỗi
 * ghi DB mới được ném để broker giao lại.
 * <p>
 * Gửi lại sau khi khắc phục: đặt bản ghi {@code DEAD_LETTERED} về {@code PENDING} (README); kênh đã gửi thành công không
 * gửi lần hai ({@code notification_logs}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedDlqListener {

    /** Property Artemis ghi vào message dead-letter: hàng đợi gốc — với topic là tên subscription. */
    static final String ORIGINAL_QUEUE_PROPERTY = DeadLetters.ORIGINAL_QUEUE_PROPERTY;

    private final MessageConverter messageConverter;
    private final OutboxService outboxService;

    // Một thread là đủ, không chiếm thêm chỗ trong ngân sách connection (ConnectionBudgetCheck)
    @JmsListener(destination = Destinations.ORDER_PLACED_DLQ, concurrency = "1")
    public void onDeadLetter(Message message) {
        String subscription = DeadLetters.originalQueueOf(message);
        Long outboxId = DeadLetters.outboxIdOf(message);
        Object payload;
        try {
            payload = messageConverter.fromMessage(message);
        } catch (JMSException | RuntimeException ex) {
            // Mọi lỗi đọc message đều là lỗi của chính message: ném ra thì broker giao lại mãi (maxDeliveryAttempts=-1)
            log.error("Unreadable {} message dead-lettered from subscription {}: {} (sent at {}, body length {}, "
                            + "outbox {})", Destinations.ORDER_PLACED, subscription, DeadLetters.describeError(ex),
                    DeadLetters.timestampOf(message), DeadLetters.bodyLength(message), DeadLetters.describeOutbox(outboxId));
            keepOutboxRecord(outboxId);
            return;
        }
        if (payload instanceof OrderPlacedMessage orderPlaced) {
            log.error("Notification for order #{} dead-lettered from subscription {} after all delivery attempts "
                    + "(outbox {})", orderPlaced.orderId(), subscription, DeadLetters.describeOutbox(outboxId));
        } else {
            log.error("Unexpected {} payload dead-lettered from subscription {} (outbox {})", Destinations.ORDER_PLACED,
                    subscription, DeadLetters.describeOutbox(outboxId));
        }
        keepOutboxRecord(outboxId);
    }

    private void keepOutboxRecord(Long outboxId) {
        if (outboxId != null && !outboxService.markDeadLettered(outboxId)) {
            // Bản ghi đã DEAD_LETTERED (subscription khác đẩy cùng message vào DLQ trước) tính là thành công, không vào đây
            log.warn("Outbox message #{} is no longer SENT (reset to PENDING or purged), left unchanged", outboxId);
        }
    }
}
