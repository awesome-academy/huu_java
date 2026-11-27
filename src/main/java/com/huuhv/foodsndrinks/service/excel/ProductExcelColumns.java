package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter.Column;

import java.util.List;

/**
 * Các cột của file Excel sản phẩm — MỘT định nghĩa cho file mẫu import, bộ đọc import và file export, để file export sửa
 * rồi import lại được. Đổi tên/thứ tự cột là đổi hợp đồng với các file người dùng đang giữ.
 */
final class ProductExcelColumns {

    static final String NAME = "Tên";
    static final String CATEGORY = "Danh mục";
    static final String TYPE = "Loại";
    static final String PRICE = "Giá";
    static final String DESCRIPTION = "Mô tả";
    static final String AVAILABLE = "Còn bán";

    /** Thứ tự cột trong file (cột A → F). */
    static final List<String> HEADERS = List.of(NAME, CATEGORY, TYPE, PRICE, DESCRIPTION, AVAILABLE);

    static final List<Column> COLUMNS = List.of(
            new Column(NAME, 32),
            new Column(CATEGORY, 22),
            new Column(TYPE, 12),
            new Column(PRICE, 14),
            new Column(DESCRIPTION, 50),
            new Column(AVAILABLE, 10));

    /** Giá trị cột "Còn bán" mà file mẫu/export ghi ra (bộ đọc còn nhận thêm TRUE/FALSE, 1/0). */
    static final String YES = "Có";
    static final String NO = "Không";

    static final String SHEET_NAME = "Sản phẩm";

    private ProductExcelColumns() {
    }
}
