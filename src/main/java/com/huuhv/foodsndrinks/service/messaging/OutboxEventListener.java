package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.service.messaging.OutboxService.OutboxMessageSavedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gửi bản ghi outbox ngay sau khi transaction ghi nó commit, để thông báo đi tức thì thay vì chờ chu kỳ của
 * {@link OutboxRelayJob}. Chạy trên pool {@code notify-*} ({@code @Async}), không trên thread request: broker chết thì
 * gửi mất tới {@code connectTimeoutMillis}/{@code callTimeout} của {@code broker-url} — khách không phải chờ. Lỗi ở
 * đây không làm mất gì: bản ghi vẫn PENDING và job gửi lại.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final OutboxPublisher outboxPublisher;

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxMessageSaved(OutboxMessageSavedEvent event) {
        try {
            outboxPublisher.publish(event.outboxId());
        } catch (RuntimeException ex) {
            // Lỗi DB khi khoá/đánh dấu bản ghi (lỗi gửi JMS đã được OutboxPublisher ghi nhận)
            log.warn("Immediate publish of outbox message #{} failed, the relay job will retry", event.outboxId(), ex);
        }
    }
}
