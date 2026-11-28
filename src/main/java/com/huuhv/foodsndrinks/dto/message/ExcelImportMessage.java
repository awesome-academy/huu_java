package com.huuhv.foodsndrinks.dto.message;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Message JMS trên queue {@code excel.import} (alias {@code excelImport} trong {@code MessageTypes}): "hãy xử lý lần import
 * này". Ghi vào outbox trong cùng transaction tạo lần import ({@code ImportJobService.submit}) → chỉ gửi sau commit.
 * <p>
 * Chỉ mang id: file, người tải lên và trạng thái đều đọc từ bảng {@code import_jobs} lúc xử lý — message giả/lặp không
 * mang theo được dữ liệu nào, và trạng thái trong DB quyết định có xử lý hay không.
 * <p>
 * Chỉ <b>thêm</b> field, không đổi tên/xoá/đổi kiểu (message đang nằm trong queue phải còn đọc được).
 *
 * @param importJobId id dòng {@code import_jobs}
 */
public record ExcelImportMessage(@NotNull @Positive Long importJobId) {
}
