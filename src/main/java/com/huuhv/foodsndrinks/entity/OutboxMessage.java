package com.huuhv.foodsndrinks.entity;

import com.huuhv.foodsndrinks.enums.OutboxStatus;
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
 * Message chờ gửi lên JMS (Transactional Outbox): ghi trong CÙNG transaction với thay đổi nghiệp vụ (checkout), gửi lên
 * broker sau đó ({@code OutboxPublisher}). Qua JPA chỉ chèn {@code type} + {@code payload} (mọi cột khác lấy default
 * của DB) và đọc; trạng thái chỉ đổi bằng UPDATE có điều kiện trong {@code OutboxMessageRepository} — không load-sửa-lưu.
 */
@Entity
@Immutable
@Table(name = "outbox_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Alias kiểu payload ({@code OutboxType}, trùng {@code MessageTypes}). */
    @Column(nullable = false, length = 64)
    private String type;

    /**
     * Payload JSON, gửi nguyên văn làm body của {@code TextMessage}. Cột {@code MEDIUMTEXT} (có CHECK {@code JSON_VALID}),
     * không phải kiểu {@code JSON} của MySQL — kiểu đó chuẩn hoá lại văn bản (số thập phân thành DOUBLE, đổi thứ tự key).
     */
    @Column(nullable = false, columnDefinition = "mediumtext")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(insertable = false, updatable = false)
    private OutboxStatus status;

    @Column(insertable = false, updatable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at", insertable = false, updatable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at", insertable = false, updatable = false)
    private LocalDateTime sentAt;

    @Column(name = "last_error", insertable = false, updatable = false)
    private String lastError;
}
