package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.logging.LogSanitizer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.springframework.jms.support.converter.MessageConversionException;

/**
 * Đọc thông tin của một message trong DLQ để ghi log — dùng chung cho các listener DLQ. Không method nào ném lỗi: listener
 * DLQ thử lại không giới hạn ({@code maxDeliveryAttempts=-1}), ném vì lỗi của chính message là lặp vô hạn. Không bao giờ
 * trả về nội dung message, chỉ định danh đã làm sạch.
 */
public final class DeadLetters {

    /** Property Artemis ghi vào message dead-letter: hàng đợi gốc — với topic là tên subscription. */
    public static final String ORIGINAL_QUEUE_PROPERTY = "_AMQ_ORIG_QUEUE";

    private static final String UNKNOWN = "?";

    private DeadLetters() {
    }

    /** Hàng đợi/subscription gốc (broker tự đặt) đã làm sạch; {@code "?"} nếu thiếu hoặc không đọc được. */
    public static String originalQueueOf(Message message) {
        try {
            return LogSanitizer.sanitize(message.getStringProperty(ORIGINAL_QUEUE_PROPERTY));
        } catch (JMSException | RuntimeException ex) {
            return UNKNOWN;
        }
    }

    /** {@code null} nếu message không đi qua outbox (gửi thẳng) hoặc property không hợp lệ/không đọc được. */
    public static Long outboxIdOf(Message message) {
        try {
            if (!message.propertyExists(MessageTypes.OUTBOX_ID_PROPERTY)) {
                return null;
            }
            long id = message.getLongProperty(MessageTypes.OUTBOX_ID_PROPERTY);
            return id > 0 ? id : null;
        } catch (JMSException | RuntimeException ex) {
            return null;
        }
    }

    public static String describeOutbox(Long outboxId) {
        return outboxId == null ? "none" : "#" + outboxId;
    }

    /**
     * Thông điệp lỗi chuyển đổi đã được {@code AliasOnlyJsonMessageConverter} làm sạch (không chứa payload/alias); lỗi
     * khác chỉ ghi tên loại lỗi — thông điệp của client JMS có thể nối nguyên dữ liệu của message.
     */
    public static String describeError(Exception ex) {
        return ex instanceof MessageConversionException ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    public static String timestampOf(Message message) {
        try {
            return String.valueOf(message.getJMSTimestamp());
        } catch (JMSException | RuntimeException ex) {
            return UNKNOWN;
        }
    }

    public static String bodyLength(Message message) {
        try {
            return message instanceof TextMessage text && text.getText() != null
                    ? String.valueOf(text.getText().length()) : UNKNOWN;
        } catch (JMSException | RuntimeException ex) {
            return UNKNOWN;
        }
    }
}
