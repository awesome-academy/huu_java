package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

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
     * Đánh dấu bản ghi có message đã vào DLQ ({@code OrderPlacedDlqListener}) để không bị job dọn dẹp xoá.
     *
     * @return {@code false} nếu bản ghi không còn ở SENT (đã bị xoá, đã đặt lại về PENDING hoặc đã đánh dấu)
     */
    @Transactional
    public boolean markDeadLettered(long outboxId) {
        return repository.markDeadLettered(outboxId) == 1;
    }

    /** Phát trong transaction của {@link #enqueue}; {@link OutboxEventListener} nhận sau khi commit. */
    public record OutboxMessageSavedEvent(long outboxId) {
    }
}
