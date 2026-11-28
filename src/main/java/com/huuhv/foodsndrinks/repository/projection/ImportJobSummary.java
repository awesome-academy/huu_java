package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.ImportJobStatus;

import java.time.LocalDateTime;

/**
 * Một lần import trong danh sách "gần đây" — projection ({@code SELECT new}) của
 * {@code ImportJobRepository.findRecentSummaries}: mọi cột trừ {@code errors} (JSON MEDIUMTEXT, tới
 * {@code max-errors} lỗi dòng) mà danh sách không hiển thị, để mỗi lần mở trang import không đọc nó cho cả 10 lần.
 */
public record ImportJobSummary(Long id, String fileName, ImportJobStatus status, Integer totalRows, Integer createdCount,
                               Integer updatedCount, Integer unchangedCount, Integer errorCount, String failureMessage,
                               String createdBy, LocalDateTime createdAt, LocalDateTime finishedAt) {
}
