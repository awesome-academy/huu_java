package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gửi mỗi thông báo (đơn, kênh) tối đa một lần dù broker giao lại message (redelivery, gửi trùng từ Outbox, nhiều
 * instance cùng subscription).
 * <p>
 * Trong MỘT transaction DB: chèn dòng {@code notification_logs} (INSERT IGNORE theo khoá unique) → nếu vừa chèn thì gửi →
 * commit. Gửi lỗi → rollback, dòng biến mất, exception thoát ra để broker giao lại. Lần giao song song cùng khoá chờ
 * khoá unique của lần trước: lần trước commit thì nhận 0 (bỏ qua), rollback thì nhận 1 (tự gửi). Commit DB xong rồi
 * mới ack JMS (listener container ack sau khi method trả về), nên app chết giữa hai bước chỉ dẫn tới một lần giao lại bị
 * bỏ qua.
 * <p>
 * Bảo đảm là <b>at-least-once</b> chứ không phải exactly-once: bên nhận có thể đã nhận thông báo trong khi app coi lần
 * gửi là lỗi, và lần giao lại gửi thêm một bản. Hai trường hợp:
 * <ul>
 *   <li>Slack/SMTP đã nhận nhưng trả lời chậm hơn read timeout ({@code AppConfig.restClient},
 *       {@code spring.mail.properties.mail.smtp.timeout}) → exception → rollback → gửi lại. Có thể xảy ra khi bên nhận
 *       chậm; tăng timeout giảm khả năng này nhưng giữ connection DB lâu hơn (xem đánh đổi bên dưới).</li>
 *   <li>Gửi xong nhưng commit DB lỗi (rất hiếm).</li>
 * </ul>
 * <p>
 * Đánh đổi: giữ một connection DB trong lúc gọi Slack/SMTP — vì vậy cả hai đều có timeout ngắn ({@code AppConfig},
 * {@code spring.mail.properties.mail.smtp.*timeout}), listener topic chạy một thread mỗi subscription
 * ({@code JmsConfig}) và tổng connection mà việc nền có thể giữ được kiểm tra lúc khởi động
 * ({@code config/ConnectionBudgetCheck}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository repository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Gửi nếu (đơn, kênh) chưa từng gửi thành công.
     *
     * @return {@code true} nếu lần này đã gửi, {@code false} nếu đã gửi trước đó (bỏ qua)
     * @throws RuntimeException lỗi của {@code send} hoặc của DB — để broker giao lại message
     */
    public boolean sendOnce(long orderId, NotificationChannel channel, Runnable send) {
        AtomicBoolean sendStarted = new AtomicBoolean();
        try {
            return attempt(orderId, channel, send, sendStarted);
        } catch (PessimisticLockingFailureException ex) {
            if (sendStarted.get()) {
                // Đã gửi: thử lại ở đây sẽ gửi lần hai — để broker giao lại (khoá đã được giải phóng khi rollback)
                throw ex;
            }
            // Deadlock/lock timeout khi chèn khoá unique (hai lần giao song song): thử lại ngay một lần
            log.warn("Lock conflict recording {} notification for order #{}, retrying once", channel, orderId);
            return attempt(orderId, channel, send, sendStarted);
        }
    }

    private boolean attempt(long orderId, NotificationChannel channel, Runnable send, AtomicBoolean sendStarted) {
        Boolean sent = new TransactionTemplate(transactionManager).execute(status -> {
            if (repository.insertIfAbsent(orderId, channel.name()) == 0) {
                return false;
            }
            sendStarted.set(true);
            send.run();
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }
}
