package com.huuhv.foodsndrinks.dto.response;

import java.util.List;

/**
 * Kết quả một lần import sản phẩm từ Excel. Có lỗi dòng ({@code errorCount > 0}) thì KHÔNG dòng nào được ghi và ba số
 * đếm thêm/cập nhật/không đổi đều là 0.
 *
 * @param totalRows  số dòng dữ liệu trong file
 * @param created    số sản phẩm đã thêm mới
 * @param updated    số sản phẩm đã cập nhật
 * @param unchanged  số dòng trùng hoàn toàn với sản phẩm đã có (không ghi gì)
 * @param errorCount tổng số lỗi của file
 * @param errors     các lỗi để hiển thị, theo thứ tự dòng rồi cột — có thể ít hơn {@code errorCount} (bị giới hạn bởi
 *                   {@code app.excel.import.max-errors})
 */
public record ProductImportResDto(int totalRows, int created, int updated, int unchanged, int errorCount,
                                  List<ImportRowErrorResDto> errors) {

    public boolean isSuccess() {
        return errorCount == 0;
    }

    /** Số lỗi không nằm trong {@link #errors} vì vượt giới hạn hiển thị. */
    public int getHiddenErrorCount() {
        return errorCount - errors.size();
    }
}
