package com.huuhv.foodsndrinks.entity;

import com.huuhv.foodsndrinks.enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Một thông báo đơn hàng đã gửi qua một kênh. Chỉ đọc qua JPA ({@link Immutable}); ghi bằng
 * {@code NotificationLogRepository.insertIfAbsent} (INSERT IGNORE theo khoá unique {@code (order_id, channel)}).
 */
@Entity
@Immutable
@Table(name = "notification_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "sent_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime sentAt;
}
