package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * Ghi nhận (đơn, kênh) nếu chưa có. Phải chạy trong transaction của người gọi: dòng vừa chèn giữ khoá unique tới
     * commit/rollback, transaction khác chèn cùng khoá sẽ chờ rồi nhận 0 (đã commit) hoặc 1 (đã rollback).
     *
     * @return 1 nếu vừa chèn (chưa gửi lần nào), 0 nếu đã có
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT IGNORE INTO notification_logs (order_id, channel) VALUES (:orderId, :channel)",
            nativeQuery = true)
    int insertIfAbsent(@Param("orderId") long orderId, @Param("channel") String channel);
}
