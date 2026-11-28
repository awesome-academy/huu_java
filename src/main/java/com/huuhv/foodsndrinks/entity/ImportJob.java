package com.huuhv.foodsndrinks.entity;

import com.huuhv.foodsndrinks.enums.ImportJobStatus;
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
 * Một lần import sản phẩm từ Excel chạy nền. Qua JPA chỉ chèn {@code fileName} + {@code storedFile} + {@code createdBy}
 * (mọi cột khác lấy default của DB) và đọc; trạng thái/kết quả chỉ đổi bằng UPDATE có điều kiện trong
 * {@code ImportJobRepository} — không load-sửa-lưu, để hai lần xử lý cùng một lần import không ghi đè nhau.
 */
@Entity
@Immutable
@Table(name = "import_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên file admin tải lên (đã cắt ngắn) — chỉ để hiển thị, không bao giờ dùng làm đường dẫn. */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** Tên file do server sinh trong {@code app.excel.import.dir} ({@code ImportFileStorage}). */
    @Column(name = "stored_file", nullable = false, length = 64)
    private String storedFile;

    @Enumerated(EnumType.STRING)
    @Column(insertable = false, updatable = false)
    private ImportJobStatus status;

    @Column(name = "total_rows", insertable = false, updatable = false)
    private Integer totalRows;

    @Column(name = "created_count", insertable = false, updatable = false)
    private Integer createdCount;

    @Column(name = "updated_count", insertable = false, updatable = false)
    private Integer updatedCount;

    @Column(name = "unchanged_count", insertable = false, updatable = false)
    private Integer unchangedCount;

    @Column(name = "error_count", insertable = false, updatable = false)
    private Integer errorCount;

    /** JSON các lỗi dòng được giữ lại để hiển thị ({@code ImportRowErrorResDto}). */
    @Column(insertable = false, updatable = false, columnDefinition = "mediumtext")
    private String errors;

    /** Lý do thất bại của cả file — câu hiển thị cho admin. */
    @Column(name = "failure_message", insertable = false, updatable = false)
    private String failureMessage;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at", insertable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at", insertable = false, updatable = false)
    private LocalDateTime finishedAt;
}
