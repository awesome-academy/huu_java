package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.enums.OutboxStatus;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Ghi message vào Transactional Outbox ({@code outbox_messages}) trong CÙNG transaction với thay đổi nghiệp vụ: đơn hàng
 * commit thì message chắc chắn có trong DB (dù broker chết), rollback thì không có. Sau commit
 * {@link OutboxEventListener} gửi ngay; gửi không được thì {@link OutboxRelayJob} gửi lại.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxMessageRepository repository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@code MANDATORY}: không có transaction thì lỗi ngay — nếu không, bản ghi được commit riêng (không còn "cùng
     * transaction với đơn hàng") và {@code @TransactionalEventListener} bị bỏ qua lặng lẽ.
     *
     * @param payload DTO đúng kiểu {@link OutboxType#payloadType()}, serialize JSON bằng {@code JsonMapper} của app (cùng
     *                cấu hình với converter JMS)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboxType type, Object payload) {
        if (!type.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Outbox " + type + " expects " + type.payloadType().getName());
        }
        OutboxMessage saved = repository.save(OutboxMessage.builder()
                .type(type.alias())
                .payload(jsonMapper.writeValueAsString(payload))
                .build());
        eventPublisher.publishEvent(new OutboxMessageSavedEvent(saved.getId()));
    }

    /**
     * Đánh dấu bản ghi có message đã vào DLQ ({@code OrderPlacedDlqListener}) để không bị job dọn dẹp xoá. Một message
     * topic có thể vào DLQ nhiều lần với cùng bản ghi — mỗi subscription lỗi đẩy một bản (vd. payload không đọc được, hoặc
     * SMTP và Slack cùng lỗi) — nên bản ghi đã {@code DEAD_LETTERED} cũng tính là thành công.
     *
     * @return {@code false} nếu bản ghi không ở SENT/DEAD_LETTERED (đã bị xoá hoặc đã đặt lại về PENDING) — giữ nguyên
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean markDeadLettered(long outboxId) {
        return repository.markDeadLettered(outboxId) == 1
                || repository.findStatusById(outboxId).filter(OutboxStatus.DEAD_LETTERED::equals).isPresent();
    }

    /**
     * Xoá tối đa {@code limit} bản ghi SENT cũ hơn {@code retention} ({@link OutboxRelayJob}). Bản ghi
     * {@code DEAD_LETTERED}/{@code FAILED} không bị xoá — còn để gửi lại bằng tay.
     * <p>
     * READ COMMITTED như {@link OutboxPublisher}: ở REPEATABLE READ (mặc định MySQL) câu DELETE quét theo khoảng trên index
     * {@code (status, sent_at)} giữ next-key/gap lock tới commit — kể cả khi không xoá dòng nào — và bản ghi mới
     * ({@code PENDING}) rơi đúng vào khoảng đó, nên INSERT outbox của checkout phải chờ. Mọi instance đều chạy job này mỗi
     * chu kỳ, nên khoá theo khoảng sẽ chặn checkout lặp lại. READ COMMITTED chỉ khoá dòng bị xoá (dòng SENT cũ, không ai
     * khác ghi); hai instance cùng xoá thì instance sau chờ khoá dòng rồi bỏ qua dòng đã bị xoá.
     *
     * @return số bản ghi đã xoá
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int purgeSent(Duration retention, int limit) {
        return repository.deleteSentBefore(retention.toSeconds(), limit);
    }

    /** Phát trong transaction của {@link #enqueue}; {@link OutboxEventListener} nhận sau khi commit. */
    public record OutboxMessageSavedEvent(long outboxId) {
    }
}
