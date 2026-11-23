package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gửi bản ghi {@code outbox_messages} lên JMS. Mỗi bản ghi một transaction ngắn: khoá dòng ({@code FOR UPDATE SKIP
 * LOCKED}) → gửi → đánh dấu {@code SENT}, hoặc ghi nhận lần lỗi ({@code FAILED} khi đủ {@code app.outbox.max-attempts}).
 * Dòng bị khoá tới commit nên gửi-ngay-sau-commit ({@link #publish}) và job ({@link #publishDue}), kể cả ở nhiều
 * instance, không bao giờ cùng gửi một bản ghi.
 * <p>
 * Gửi thành công mà commit DB lỗi (hiếm) thì bản ghi còn PENDING và được gửi lại — consumer idempotent
 * ({@code notification_logs}) nên không gửi thông báo lần hai.
 * <p>
 * Payload gửi nguyên văn (đã serialize lúc ghi outbox) trong {@code TextMessage}, kiểu ghi bằng alias ở property
 * {@value MessageTypes#TYPE_ID_PROPERTY} — consumer đọc qua {@code AliasOnlyJsonMessageConverter} như mọi message khác.
 */
@Slf4j
@Service
public class OutboxPublisher {

    /** {@code last_error} chỉ chứa tên loại lỗi (không chứa thông điệp có thể kèm host/dữ liệu). */
    static final int MAX_ERROR_LENGTH = 255;

    private final OutboxMessageRepository repository;
    private final JmsTemplate queueJmsTemplate;
    private final JmsTemplate topicJmsTemplate;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(OutboxMessageRepository repository, JmsTemplate queueJmsTemplate,
                           @Qualifier(JmsBrokerProperties.TOPIC_JMS_TEMPLATE) JmsTemplate topicJmsTemplate,
                           OutboxProperties properties, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.queueJmsTemplate = queueJmsTemplate;
        this.topicJmsTemplate = topicJmsTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Gửi một bản ghi vừa commit (gọi từ {@link OutboxEventListener}). Bản ghi đã được gửi, đang bị job khoá, hay chưa
     * tới hạn thử lại thì bỏ qua.
     *
     * @return {@code true} nếu đã gửi lên broker
     */
    public boolean publish(long outboxId) {
        return Boolean.TRUE.equals(publishLocked(() -> repository.lockDueById(outboxId)));
    }

    /**
     * Gửi các bản ghi PENDING đã tới hạn (job), tối đa {@code app.outbox.batch-size}. Dừng ở lần gửi lỗi đầu tiên: broker
     * đang chết thì gửi tiếp chỉ tốn thêm timeout và số lần thử của các bản ghi khác — chúng được thử ở chu kỳ sau.
     *
     * @return số bản ghi đã gửi
     */
    public int publishDue() {
        int sent = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            Boolean result = publishLocked(repository::lockNextDue);
            if (result == null) {
                break;               // hết bản ghi tới hạn (hoặc còn lại đều đang bị instance khác khoá)
            }
            if (!result) {
                break;               // gửi lỗi: để chu kỳ sau
            }
            sent++;
        }
        return sent;
    }

    /** @return {@code null} nếu không lấy được bản ghi nào, {@code true}/{@code false} = gửi được/lỗi */
    private Boolean publishLocked(Supplier<Optional<OutboxMessage>> lock) {
        return transactionTemplate.execute(status -> lock.get().map(this::send).orElse(null));
    }

    private boolean send(OutboxMessage message) {
        long id = message.getId();
        Optional<OutboxType> type = OutboxType.fromAlias(message.getType());
        try {
            OutboxType outboxType = type.orElseThrow(() -> new IllegalStateException("Unknown outbox type"));
            JmsTemplate template = outboxType.topic() ? topicJmsTemplate : queueJmsTemplate;
            template.send(outboxType.destination(), session -> {
                TextMessage jms = session.createTextMessage(message.getPayload());
                jms.setStringProperty(MessageTypes.TYPE_ID_PROPERTY, outboxType.alias());
                return jms;
            });
            repository.markSent(id);
            log.debug("Outbox message #{} ({}) sent", id, outboxType.alias());
            return true;
        } catch (RuntimeException ex) {
            recordFailure(message, ex);
            return false;
        }
    }

    private void recordFailure(OutboxMessage message, RuntimeException ex) {
        long id = message.getId();
        int attempt = (message.getAttempts() == null ? 0 : message.getAttempts()) + 1;
        String error = LogSanitizer.truncate(ex.getClass().getSimpleName(), MAX_ERROR_LENGTH);
        repository.markAttemptFailed(id, properties.maxAttempts(), properties.retryDelay().toMillis(), error);
        String type = LogSanitizer.sanitize(message.getType());
        if (attempt >= properties.maxAttempts()) {
            log.error("Outbox message #{} ({}) FAILED after {} attempts, giving up (reset to PENDING to resend): {}",
                    id, type, attempt, error, ex);
        } else {
            log.warn("Outbox message #{} ({}) send attempt {}/{} failed, will retry: {}",
                    id, type, attempt, properties.maxAttempts(), error);
        }
    }
}
