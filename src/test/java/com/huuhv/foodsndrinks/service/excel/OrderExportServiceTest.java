package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.projection.OrderExportRow;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test {@link OrderExportService}: nội dung file được đọc lại bằng POI. */
@ExtendWith(MockitoExtension.class)
class OrderExportServiceTest {

    private static final int MAX_ROWS = 50;

    @Mock private OrderService orderService;
    @Mock private AuditLogService auditLogService;

    private OrderExportService service;

    @BeforeEach
    void setUp() {
        service = new OrderExportService(orderService, new ExcelProperties(new ExcelProperties.Export(MAX_ROWS)),
                auditLogService);
    }

    @Test
    void export_writesOneRowPerOrder_withTheColumnsOfTheIssue() throws IOException {
        LocalDateTime orderedAt = LocalDateTime.of(2026, 11, 20, 9, 30);
        when(orderService.findExportRows("PENDING", "alice", 7L, "2026-11-01", "2026-11-30", MAX_ROWS))
                .thenReturn(List.of(
                        new OrderExportRow(12L, "Nguyễn Văn A", "alice", orderedAt, OrderStatus.PENDING,
                                new BigDecimal("125000.00"), 3L),
                        new OrderExportRow(11L, " ", "bob", null, OrderStatus.COMPLETED, new BigDecimal("40000"), 0L)));

        try (ExcelFile file = service.export("PENDING", "alice", 7L, "2026-11-01", "2026-11-30");
             XSSFWorkbook workbook = reopen(file)) {
            assertThat(file.filename())
                    .isEqualTo("foodsndrinks-orders-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx");
            assertThat(file.rowCount()).isEqualTo(2);

            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Đơn hàng");
            assertThat(sheet.getRow(0)).extracting(Cell::getStringCellValue)
                    .containsExactly("Mã đơn", "Khách hàng", "Ngày đặt", "Trạng thái", "Số món", "Tổng tiền");

            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getNumericCellValue()).isEqualTo(12d);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("Nguyễn Văn A");
            assertThat(first.getCell(2).getLocalDateTimeCellValue()).isEqualTo(orderedAt);
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo(OrderStatus.PENDING.getLabel());
            assertThat(first.getCell(4).getNumericCellValue()).isEqualTo(3d);
            assertThat(first.getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(5).getNumericCellValue()).isEqualTo(125_000d);

            // Không có họ tên → dùng username; chưa có ngày đặt → ô trống
            Row second = sheet.getRow(2);
            assertThat(second.getCell(1).getStringCellValue()).isEqualTo("bob");
            assertThat(second.getCell(2).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
        verify(auditLogService).dataExported("orders", 2);
    }

    @Test
    void customerNameThatLooksLikeAFormula_isWrittenAsQuotePrefixedText() throws IOException {
        when(orderService.findExportRows(null, null, null, null, null, MAX_ROWS)).thenReturn(List.of(
                new OrderExportRow(1L, "=HYPERLINK(\"http://evil.example\")", "mallory", LocalDateTime.now(),
                        OrderStatus.PENDING, BigDecimal.TEN, 1L)));

        try (ExcelFile file = service.export(null, null, null, null, null); XSSFWorkbook workbook = reopen(file)) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("=HYPERLINK(\"http://evil.example\")");
            assertThat(cell.getCellStyle().getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void noMatchingOrders_stillProducesAFileWithOnlyTheHeader() throws IOException {
        when(orderService.findExportRows(null, null, null, null, null, MAX_ROWS)).thenReturn(List.of());

        try (ExcelFile file = service.export(null, null, null, null, null); XSSFWorkbook workbook = reopen(file)) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(file.rowCount()).isZero();
        }
    }

    @Test
    void invalidFilterOrTooManyRows_propagates_andNothingIsAudited() {
        when(orderService.findExportRows(null, null, null, "2026-11-30", "2026-11-01", MAX_ROWS))
                .thenThrow(new IllegalArgumentException("Ngày bắt đầu không được sau ngày kết thúc!"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.export(null, null, null, "2026-11-30", "2026-11-01"))
                .withMessage("Ngày bắt đầu không được sau ngày kết thúc!");
        verifyNoInteractions(auditLogService);
    }

    private static XSSFWorkbook reopen(ExcelFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.writeTo(out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }
}
