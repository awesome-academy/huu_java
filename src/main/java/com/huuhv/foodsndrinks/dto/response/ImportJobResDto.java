package com.huuhv.foodsndrinks.dto.response;

import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.repository.projection.ImportJobSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trạng thái một lần import Excel — cho trang {@code /admin/products/import/{id}} (SSR) và JSON
 * {@code GET /admin/products/import/{id}/status} mà trang đó gọi lại để tự cập nhật. Chỉ thêm field (JS đang chạy trên
 * trang đã mở đọc JSON này).
 *
 * @param status       tên hằng {@link ImportJobStatus} — hợp đồng JSON
 * @param statusLabel  nhãn tiếng Việt của trạng thái
 * @param badgeClass   class Bootstrap của badge trạng thái
 * @param terminal     đã kết thúc (COMPLETED/FAILED) → trang ngừng tự cập nhật
 * @param errorCount   tổng số lỗi dòng của file
 * @param errors       các lỗi dòng được giữ lại để hiển thị (có thể ít hơn {@code errorCount})
 * @param failureMessage lý do thất bại của cả file (file bị từ chối, lỗi hệ thống) — câu hiển thị cho admin
 */
public record ImportJobResDto(Long id, String fileName, ImportJobStatus status, String statusLabel, String badgeClass,
                              boolean terminal, int totalRows, int created, int updated, int unchanged, int errorCount,
                              List<ImportRowErrorResDto> errors, String failureMessage, String createdBy,
                              LocalDateTime createdAt, LocalDateTime finishedAt) {

    public static ImportJobResDto of(ImportJob job, List<ImportRowErrorResDto> errors) {
        ImportJobStatus status = job.getStatus();
        return new ImportJobResDto(job.getId(), job.getFileName(), status, status.getLabel(), status.getBadgeClass(),
                status.isTerminal(), orZero(job.getTotalRows()), orZero(job.getCreatedCount()),
                orZero(job.getUpdatedCount()), orZero(job.getUnchangedCount()), orZero(job.getErrorCount()),
                List.copyOf(errors), job.getFailureMessage(), job.getCreatedBy(), job.getCreatedAt(),
                job.getFinishedAt());
    }

    /** Dòng của danh sách "gần đây": projection không có bảng lỗi → {@code errors} rỗng ({@code errorCount} vẫn đúng). */
    public static ImportJobResDto of(ImportJobSummary job) {
        ImportJobStatus status = job.status();
        return new ImportJobResDto(job.id(), job.fileName(), status, status.getLabel(), status.getBadgeClass(),
                status.isTerminal(), orZero(job.totalRows()), orZero(job.createdCount()), orZero(job.updatedCount()),
                orZero(job.unchangedCount()), orZero(job.errorCount()), List.of(), job.failureMessage(),
                job.createdBy(), job.createdAt(), job.finishedAt());
    }

    /** Số lỗi không nằm trong {@link #errors} vì vượt giới hạn lưu. */
    public int hiddenErrorCount() {
        return Math.max(0, errorCount - errors.size());
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
