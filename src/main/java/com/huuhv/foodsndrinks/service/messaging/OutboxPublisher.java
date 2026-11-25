package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.api.core.ActiveMQAddressFullException;
import org.apache.activemq.artemis.api.core.ActiveMQConnectionTimedOutException;
import org.apache.activemq.artemis.api.core.ActiveMQDisconnectedException;
import org.apache.activemq.artemis.api.core.ActiveMQIOErrorException;
import org.apache.activemq.artemis.api.core.ActiveMQInterruptedException;
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.ActiveMQRemoteDisconnectException;
import org.apache.activemq.artemis.api.core.ActiveMQShutdownException;
import org.apache.activemq.artemis.api.core.ActiveMQTimeoutException;
import org.apache.activemq.artemis.api.core.ActiveMQUnBlockedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gửi bản ghi {@code outbox_messages} lên JMS. Mỗi bản ghi một transaction ngắn: khoá dòng ({@code FOR UPDATE SKIP
 * LOCKED}) → gửi → đánh dấu. Dòng bị khoá tới commit nên gửi-ngay-sau-commit ({@link #publish}) và job
 * ({@link #publishDue}), kể cả ở nhiều instance, không bao giờ cùng gửi một bản ghi. Transaction chạy ở READ COMMITTED:
 * câu khoá theo khoảng ở REPEATABLE READ (mặc định MySQL) còn giữ gap lock, có thể chặn INSERT outbox của checkout
 * trong lúc gửi.
 * <p>
 * Gửi lỗi được phân loại:
 * <ul>
 *   <li><b>Lỗi của broker</b> — lỗi gửi là hết thời gian chờ / mất kết nối / address đầy ({@link #isBrokerFailure}), hoặc
 *       một kết nối mới tới broker không mở được ({@link JmsBrokerProbe}; không tin connection đã cache): KHÔNG tính lần
 *       thử, bản ghi giữ PENDING tới khi broker hoạt động lại — broker chết/treo bao lâu cũng không làm mất thông báo. Job
 *       dừng chu kỳ ngay (gửi tiếp chỉ tốn thêm timeout).</li>
 *   <li><b>Lỗi riêng của bản ghi</b> (broker vẫn trả lời: loại không còn trong {@link OutboxType}, message bị broker từ
 *       chối…): tính một lần thử; đủ {@code app.outbox.max-attempts} → {@code FAILED} + log ERROR. Job chuyển sang bản ghi
 *       kế tiếp, một bản ghi hỏng không chặn các bản ghi khác.</li>
 * </ul>
 * Gửi thành công mà commit DB lỗi (hiếm) thì bản ghi còn PENDING và được gửi lại — consumer idempotent
 * ({@code notification_logs}) nên không gửi thông báo lần hai. Broker chặn producer (đĩa đầy, address {@code BLOCK})
 * thì client Artemis chờ credit không giới hạn thời gian — vì vậy {@code artemis/broker.properties} đặt
 * {@code addressFullMessagePolicy=FAIL} cho address của outbox: address đầy → gửi lỗi ngay
 * ({@code ActiveMQAddressFullException}, lỗi của broker), bản ghi giữ PENDING. Destination mới gửi qua outbox cũng
 * phải đặt như vậy.
 * <p>
 * Payload gửi nguyên văn (đã serialize lúc ghi outbox, cột {@code MEDIUMTEXT} giữ đúng từng byte) trong
 * {@code TextMessage}, kiểu ghi bằng alias ở property {@value MessageTypes#TYPE_ID_PROPERTY} — consumer đọc qua
 * {@code AliasOnlyJsonMessageConverter} như mọi message khác. Property {@value MessageTypes#OUTBOX_ID_PROPERTY} mang id
 * bản ghi để {@code OrderPlacedDlqListener} đánh dấu bản ghi có message vào DLQ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    /** {@code last_error} chỉ chứa tên loại lỗi (không chứa thông điệp có thể kèm host/dữ liệu). */
    static final int MAX_ERROR_LENGTH = 255;

    /** Kết quả gửi một bản ghi. */
    enum Outcome { SENT, RECORD_FAILED, BROKER_UNAVAILABLE }

    private final OutboxMessageRepository repository;
    /** {@code @Primary} — destination kiểu queue. */
    private final JmsTemplate queueJmsTemplate;
    @Qualifier(JmsBrokerProperties.TOPIC_JMS_TEMPLATE)
    private final JmsTemplate topicJmsTemplate;
    private final OutboxProperties properties;
    private final PlatformTransactionManager transactionManager;
    private final JmsBrokerProbe brokerProbe;

    /**
     * Gửi một bản ghi vừa commit (gọi từ {@link OutboxEventListener}). Bản ghi đã được gửi, đang bị job khoá, hay chưa
     * tới hạn thử lại thì bỏ qua.
     *
     * @return {@code true} nếu đã gửi lên broker
     */
    public boolean publish(long outboxId) {
        Attempt attempt = publishLocked(() -> repository.lockDueById(outboxId));
        return attempt != null && attempt.outcome() == Outcome.SENT;
    }

    /**
     * Một chu kỳ của job: gửi các bản ghi PENDING đã tới hạn theo id tăng dần, mỗi bản ghi tối đa một lần, tối đa
     * {@code app.outbox.batch-size} bản ghi. Dừng khi broker không kết nối được.
     *
     * @return số bản ghi đã gửi
     */
    public int publishDue() {
        int sent = 0;
        long cursor = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            long afterId = cursor;
            Attempt attempt = publishLocked(() -> repository.lockNextDueAfter(afterId));
            if (attempt == null) {
                break;               // hết bản ghi tới hạn (hoặc còn lại đều đang bị instance khác khoá)
            }
            if (attempt.outcome() == Outcome.BROKER_UNAVAILABLE) {
                log.warn("JMS broker unavailable, outbox relay paused until the next cycle");
                break;
            }
            if (attempt.outcome() == Outcome.SENT) {
                sent++;
            }
            cursor = attempt.outboxId();
        }
        return sent;
    }

    /** Kết quả xử lý một bản ghi đã khoá. */
    record Attempt(long outboxId, Outcome outcome) {
    }

    /** @return {@code null} nếu không lấy được bản ghi nào */
    private Attempt publishLocked(Supplier<Optional<OutboxMessage>> lock) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> lock.get()
                .map(message -> new Attempt(message.getId(), send(message)))
                .orElse(null));
    }

    private Outcome send(OutboxMessage message) {
        Optional<OutboxType> type = OutboxType.fromAlias(message.getType());
        if (type.isEmpty()) {
            return recordFailure(message, new IllegalStateException("Unknown outbox type"));
        }
        OutboxType outboxType = type.get();
        try {
            JmsTemplate template = outboxType.topic() ? topicJmsTemplate : queueJmsTemplate;
            template.send(outboxType.destination(), session -> {
                TextMessage jms = session.createTextMessage(message.getPayload());
                jms.setStringProperty(MessageTypes.TYPE_ID_PROPERTY, outboxType.alias());
                jms.setLongProperty(MessageTypes.OUTBOX_ID_PROPERTY, message.getId());
                return jms;
            });
        } catch (JmsException ex) {
            if (isBrokerFailure(ex) || !brokerProbe.reachable()) {
                // Không ghi gì: bản ghi giữ nguyên PENDING và số lần thử
                log.warn("JMS broker unavailable ({}), outbox message #{} stays PENDING for the relay job",
                        ex.getClass().getSimpleName(), message.getId());
                return Outcome.BROKER_UNAVAILABLE;
            }
            return recordFailure(message, ex);
        } catch (RuntimeException ex) {
            return recordFailure(message, ex);
        }
        repository.markSent(message.getId());
        log.debug("Outbox message #{} ({}) sent", message.getId(), outboxType.alias());
        return Outcome.SENT;
    }

    /**
     * Lỗi gửi do broker chứ không do message: hết thời gian chờ broker trả lời (broker treo, mạng chập chờn), mất kết
     * nối, address đầy, broker đang tắt. Client Artemis gắn lỗi gốc làm {@code cause} của {@code JMSException}.
     */
    static boolean isBrokerFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ActiveMQConnectionTimedOutException || t instanceof ActiveMQTimeoutException
                    || t instanceof ActiveMQNotConnectedException || t instanceof ActiveMQDisconnectedException
                    || t instanceof ActiveMQRemoteDisconnectException || t instanceof ActiveMQUnBlockedException
                    || t instanceof ActiveMQAddressFullException || t instanceof ActiveMQIOErrorException
                    || t instanceof ActiveMQShutdownException || t instanceof ActiveMQInterruptedException) {
                return true;
            }
        }
        return false;
    }

    private Outcome recordFailure(OutboxMessage message, RuntimeException ex) {
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
        return Outcome.RECORD_FAILED;
    }
}
