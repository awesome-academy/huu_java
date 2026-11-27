package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.repository.projection.OrderExportRow;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter.Column;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Xuất danh sách đơn hàng của admin ra .xlsx — đúng các đơn mà trang {@code /admin/orders} liệt kê với cùng bộ lọc.
 * <p>
 * Cố ý không {@code @Transactional}: dữ liệu được đọc xong trong transaction read-only ngắn của
 * {@link OrderService#findExportRows} (projection, không entity), file được dựng sau đó mà không giữ connection.
 */
@Service
@RequiredArgsConstructor
public class OrderExportService {

    static final String SHEET_NAME = "Đơn hàng";
    static final List<Column> COLUMNS = List.of(
            new Column("Mã đơn", 10),
            new Column("Khách hàng", 30),
            new Column("Ngày đặt", 18),
            new Column("Trạng thái", 16),
            new Column("Số món", 10),
            new Column("Tổng tiền", 16));

    private static final String AUDIT_DATASET = "orders";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final OrderService orderService;
    private final ExcelProperties properties;
    private final AuditLogService auditLogService;

    /**
     * @return file đã dựng xong — người gọi phải đóng sau khi ghi ra response
     * @throws IllegalArgumentException bộ lọc sai (ngày không hợp lệ, ngày bắt đầu sau ngày kết thúc) hoặc khớp nhiều đơn
     *                                  hơn {@code app.excel.export.max-rows}; không có file nào được tạo
     */
    public ExcelFile export(String status, String keyword, Long orderId, String from, String to) {
        List<OrderExportRow> rows = orderService.findExportRows(status, keyword, orderId, from, to,
                properties.export().maxRows());

        ExcelSheetWriter sheet = new ExcelSheetWriter(SHEET_NAME, COLUMNS);
        try {
            for (OrderExportRow row : rows) {
                sheet.row()
                        .integer(row.id())
                        .text(customerName(row))
                        .dateTime(row.orderedAt())
                        .text(row.status().getLabel())
                        .integer(row.itemCount() == null ? 0 : row.itemCount())
                        .money(row.totalPrice());
            }
        } catch (RuntimeException e) {
            sheet.close();
            throw e;
        }

        auditLogService.dataExported(AUDIT_DATASET, rows.size());
        return new ExcelFile("foodsndrinks-orders-" + LocalDate.now().format(FILE_DATE) + ".xlsx", rows.size(), sheet);
    }

    private static String customerName(OrderExportRow row) {
        String fullName = row.customerFullName();
        return fullName == null || fullName.isBlank() ? row.customerUsername() : fullName;
    }
}
