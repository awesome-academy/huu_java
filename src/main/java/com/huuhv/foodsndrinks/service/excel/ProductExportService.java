package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.repository.projection.ProductExportRow;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Xuất danh sách sản phẩm của admin ra .xlsx — đúng các sản phẩm mà trang {@code /admin/products} liệt kê với cùng bộ lọc.
 * <p>
 * File có ĐÚNG các cột và cách ghi giá trị của file mẫu import ({@link ProductExcelColumns}: Loại ghi bằng nhãn, "Còn bán"
 * là Có/Không, Giá là ô số): sửa file rồi import lại sẽ cập nhật các sản phẩm đó (import là upsert theo tên), dòng không
 * sửa được tính là "không đổi". Sản phẩm không còn danh mục xuất ra ô Danh mục trống — import lại sẽ báo lỗi dòng đó cho
 * tới khi điền danh mục (form admin cũng bắt buộc danh mục).
 * <p>
 * Như {@link OrderExportService}: cố ý không {@code @Transactional}, dữ liệu đọc xong (projection) rồi mới dựng file.
 */
@Service
@RequiredArgsConstructor
public class ProductExportService {

    private static final String AUDIT_DATASET = "products";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ProductService productService;
    private final ExcelProperties properties;
    private final AuditLogService auditLogService;

    /**
     * @return file đã dựng xong — người gọi phải đóng sau khi ghi ra response
     * @throws IllegalArgumentException bộ lọc khớp nhiều sản phẩm hơn {@code app.excel.export.max-rows}; không có file nào
     *                                  được tạo
     */
    public ExcelFile export(String name, Long categoryId, String type, String isAvailable) {
        List<ProductExportRow> rows = productService.findExportRows(name, categoryId, type, isAvailable,
                properties.export().maxRows());

        ExcelSheetWriter sheet = new ExcelSheetWriter(ProductExcelColumns.SHEET_NAME, ProductExcelColumns.COLUMNS);
        try {
            for (ProductExportRow row : rows) {
                sheet.row()
                        .text(row.name())
                        .text(row.categoryName())
                        .text(row.type() == null ? null : row.type().getLabel())
                        .decimal(row.price())
                        .text(row.description())
                        .text(Boolean.TRUE.equals(row.isAvailable()) ? ProductExcelColumns.YES : ProductExcelColumns.NO);
            }
        } catch (RuntimeException e) {
            sheet.close();
            throw e;
        }

        auditLogService.dataExported(AUDIT_DATASET, rows.size());
        return new ExcelFile("foodsndrinks-products-" + LocalDate.now().format(FILE_DATE) + ".xlsx", rows.size(), sheet);
    }
}
