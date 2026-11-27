package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.service.messaging.DeadLetters;
import com.huuhv.foodsndrinks.service.messaging.Destinations;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * Message của queue {@code excel.import} đã hết số lần giao và bị broker chuyển vào {@code DLQ.excel-import}: đánh dấu
 * lần import thất bại vì lỗi hệ thống (admin thấy ngay trên trang trạng thái, không phải chờ job dọn dẹp), xoá file tạm,
 * đánh dấu bản ghi outbox {@code DEAD_LETTERED}, rồi lấy message khỏi DLQ.
 * <p>
 * Như {@code OrderPlacedDlqListener}: nhận {@link Message} thô và tự chuyển đổi; message không đọc được chỉ được ghi log
 * (không ném — DLQ thử lại không giới hạn). Chỉ lỗi ghi DB mới được ném để broker giao lại message DLQ.
 * <p>
 * Lần import đã kết thúc trước đó (một lần xử lý muộn vẫn xong) thì giữ nguyên: mọi thay đổi trạng thái đều có điều kiện.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelImportDlqListener {

    private final MessageConverter messageConverter;
    private final ImportJobService importJobService;
    private final OutboxService outboxService;

    @JmsListener(destination = Destinations.EXCEL_IMPORT_DLQ, concurrency = "1")
    public void onDeadLetter(Message message) {
        Long outboxId = DeadLetters.outboxIdOf(message);
        Object payload;
        try {
            payload = messageConverter.fromMessage(message);
        } catch (JMSException | RuntimeException ex) {
            log.error("Unreadable {} message dead-lettered: {} (sent at {}, body length {}, outbox {})",
                    Destinations.EXCEL_IMPORT, DeadLetters.describeError(ex), DeadLetters.timestampOf(message),
                    DeadLetters.bodyLength(message), DeadLetters.describeOutbox(outboxId));
            keepOutboxRecord(outboxId);
            return;
        }
        if (payload instanceof ExcelImportMessage(Long importJobId) && importJobId != null && importJobId > 0) {
            boolean failed = importJobService.failDeadLettered(importJobId);
            log.error("Import job #{} dead-lettered after all delivery attempts (outbox {}): {}", importJobId,
                    DeadLetters.describeOutbox(outboxId), failed ? "marked FAILED" : "already finished, left unchanged");
        } else {
            log.error("Unexpected {} payload dead-lettered (outbox {})", Destinations.EXCEL_IMPORT,
                    DeadLetters.describeOutbox(outboxId));
        }
        keepOutboxRecord(outboxId);
    }

    private void keepOutboxRecord(Long outboxId) {
        if (outboxId != null && !outboxService.markDeadLettered(outboxId)) {
            log.warn("Outbox message #{} is no longer SENT (reset to PENDING or purged), left unchanged", outboxId);
        }
    }
}
