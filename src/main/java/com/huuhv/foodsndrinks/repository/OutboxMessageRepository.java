package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Bảng {@code outbox_messages}. Chỉ {@code service/messaging/OutboxService} và {@code OutboxPublisher} dùng repository
 * này. Các method {@code lock*} phải chạy trong transaction: dòng trả về bị khoá ({@code FOR UPDATE}) tới commit, dòng
 * đang bị transaction khác khoá thì bị bỏ qua ({@code SKIP LOCKED}, MySQL 8) — nhiều instance/thread chạy song song không
 * gửi trùng một bản ghi. Thời gian luôn là đồng hồ của DB.
 */
@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Bản ghi PENDING đã tới hạn có id lớn hơn {@code afterId}, nhỏ nhất trước. Job duyệt bằng con trỏ id tăng dần nên mỗi
     * chu kỳ thử mỗi bản ghi tối đa một lần — bản ghi vừa lỗi (vẫn PENDING, có thể đã tới hạn lại) không bị chọn lại và
     * không chặn các bản ghi sau nó.
     */
    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(3) AND id > :afterId
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxMessage> lockNextDueAfter(@Param("afterId") long afterId);

    /** Bản ghi {@code id} nếu còn PENDING, đã tới hạn và không bị ai khoá. */
    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(3)
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxMessage> lockDueById(@Param("id") long id);

    @Modifying
    @Query(value = """
            UPDATE outbox_messages
            SET status = 'SENT', attempts = attempts + 1, sent_at = CURRENT_TIMESTAMP(3), last_error = NULL
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markSent(@Param("id") long id);

    /**
     * Ghi nhận một lần gửi lỗi: đủ {@code maxAttempts} lần thì {@code FAILED}, không thì hẹn lần sau. MySQL gán SET từ
     * trái sang phải và vế sau thấy giá trị mới → {@code status} được tính TRƯỚC khi tăng {@code attempts}.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_messages
            SET status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                attempts = attempts + 1,
                next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :retryDelayMillis * 1000 MICROSECOND),
                last_error = :error
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markAttemptFailed(@Param("id") long id, @Param("maxAttempts") int maxAttempts,
                          @Param("retryDelayMillis") long retryDelayMillis, @Param("error") String error);

    /**
     * Message của bản ghi SENT đã vào DLQ: chuyển sang DEAD_LETTERED để job dọn dẹp không xoá (còn gửi lại được). Chỉ đổi
     * từ SENT — bản ghi đã đặt lại về PENDING hay đã đánh dấu thì giữ nguyên.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_messages SET status = 'DEAD_LETTERED'
            WHERE id = :id AND status = 'SENT'
            """, nativeQuery = true)
    int markDeadLettered(@Param("id") long id);

    /** Xoá tối đa {@code limit} bản ghi SENT cũ hơn {@code retentionSeconds} (transaction riêng, gọi từ job). */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM outbox_messages
            WHERE status = 'SENT' AND sent_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :retentionSeconds SECOND)
            LIMIT :limit
            """, nativeQuery = true)
    int deleteSentBefore(@Param("retentionSeconds") long retentionSeconds, @Param("limit") int limit);
}
