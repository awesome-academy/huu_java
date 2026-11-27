package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;

import java.util.List;

/**
 * Kết quả validate một file import — việc cần ghi nếu file không có lỗi. Chưa có gì được ghi vào DB.
 *
 * @param totalRows      số dòng dữ liệu trong file
 * @param commands       các thao tác thêm/cập nhật, theo thứ tự dòng (rỗng khi có lỗi)
 * @param unchangedCount số dòng trùng hoàn toàn với sản phẩm đã có — không cần ghi
 * @param errors         MỌI lỗi của file, theo thứ tự dòng rồi cột
 */
public record ProductImportPlan(int totalRows, List<ProductImportCommand> commands, int unchangedCount,
                                List<ImportRowErrorResDto> errors) {

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public int createCount() {
        return (int) commands.stream().filter(ProductImportCommand::isCreate).count();
    }

    public int updateCount() {
        return commands.size() - createCount();
    }
}
