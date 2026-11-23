package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gửi lại bản ghi outbox PENDING đã tới hạn (broker chết lúc gửi ngay sau commit, hoặc app tắt trước khi kịp gửi) và dọn
 * bản ghi SENT cũ.
 * <p>
 * Không dùng {@code @SchedulerLock} (ngoại lệ có chủ ý so với quy ước job của dự án): mọi instance cùng chạy job, mỗi
 * bản ghi được khoá riêng bằng {@code FOR UPDATE SKIP LOCKED} nên các instance chia nhau bản ghi, không gửi trùng; một
 * instance chết không làm cả hệ thống ngừng gửi lại. Câu DELETE dọn bản ghi chạy song song cũng an toàn. Không
 * {@code @Transactional} trên method job: mỗi bản ghi một transaction ngắn ({@link OutboxPublisher}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    /** Số dòng SENT tối đa xoá mỗi câu DELETE (giữ transaction/khoá ngắn). */
    static final int CLEANUP_BATCH_SIZE = 1000;

    private final OutboxPublisher outboxPublisher;
    private final OutboxService outboxService;
    private final OutboxProperties properties;

    @Scheduled(initialDelayString = "${app.outbox.poll-interval:1m}", fixedDelayString = "${app.outbox.poll-interval:1m}")
    public void relay() {
        try {
            int sent = outboxPublisher.publishDue();
            if (sent > 0) {
                log.info("Outbox relay sent {} pending message(s)", sent);
            }
        } catch (RuntimeException ex) {
            log.error("Outbox relay failed, will retry next cycle", ex);
        }
        try {
            int deleted = outboxService.purgeSent(properties.retention(), CLEANUP_BATCH_SIZE);
            if (deleted > 0) {
                log.debug("Deleted {} sent outbox message(s) older than {}", deleted, properties.retention());
            }
        } catch (RuntimeException ex) {
            log.warn("Cleaning up sent outbox messages failed", ex);
        }
    }
}
