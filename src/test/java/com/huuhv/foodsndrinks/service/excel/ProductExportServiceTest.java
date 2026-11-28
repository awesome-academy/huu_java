package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.repository.projection.ProductExportRow;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test {@link ProductExportService}: nội dung file được đọc lại bằng POI và bằng chính bộ đọc import. */
@ExtendWith(MockitoExtension.class)
class ProductExportServiceTest {

    private static final int MAX_ROWS = 50;

    @TempDir Path directory;

    @Mock private ProductService productService;
    @Mock private AuditLogService auditLogService;

    private ProductExportService service;

    @BeforeEach
    void setUp() {
        service = new ProductExportService(productService, ExcelTestFiles.properties(MAX_ROWS, 1_000), auditLogService);
    }

    @Test
    void export_writesOneRowPerProduct_withTheColumnsOfTheImportTemplate() throws IOException {
        when(productService.findExportRows("trà", 10L, "DRINK", "true", MAX_ROWS)).thenReturn(List.of(
                new ProductExportRow("Trà đào", "Đồ uống", ProductType.DRINK, new BigDecimal("35000.50"), "Cam sả", true),
                new ProductExportRow("Cơm tấm", null, ProductType.FOOD, new BigDecimal("45000.00"), null, false)));

        try (ExcelFile file = service.export("trà", 10L, "DRINK", "true"); XSSFWorkbook workbook = reopen(file)) {
            assertThat(file.filename()).isEqualTo(
                    "foodsndrinks-products-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx");
            assertThat(file.rowCount()).isEqualTo(2);

            Sheet sheet = workbook.getSheetAt(0);
            assertThat((Iterable<Cell>) sheet.getRow(0)).extracting(Cell::getStringCellValue)
                    .containsExactly("Tên", "Danh mục", "Loại", "Giá", "Mô tả", "Còn bán");

            Row tea = sheet.getRow(1);
            assertThat(tea.getCell(0).getStringCellValue()).isEqualTo("Trà đào");
            assertThat(tea.getCell(1).getStringCellValue()).isEqualTo("Đồ uống");
            assertThat(tea.getCell(2).getStringCellValue()).isEqualTo("Đồ uống");
            // Giá là ô số, giữ đủ phần thập phân
            assertThat(tea.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(tea.getCell(3).getNumericCellValue()).isEqualTo(35000.5);
            assertThat(tea.getCell(4).getStringCellValue()).isEqualTo("Cam sả");
            assertThat(tea.getCell(5).getStringCellValue()).isEqualTo("Có");

            // Sản phẩm không còn danh mục / không có mô tả → ô trống; ngừng bán → "Không"
            Row rice = sheet.getRow(2);
            assertThat(rice.getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(rice.getCell(2).getStringCellValue()).isEqualTo("Đồ ăn");
            assertThat(rice.getCell(4).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(rice.getCell(5).getStringCellValue()).isEqualTo("Không");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
        verify(auditLogService).dataExported("products", 2);
    }

    @Test
    void valuesThatLookLikeFormulas_areWrittenAsQuotePrefixedText_withTheirValueUnchanged() throws IOException {
        when(productService.findExportRows(null, null, null, null, MAX_ROWS)).thenReturn(List.of(
                new ProductExportRow("=HYPERLINK(\"http://evil.example\")", "+Khuyến mãi", ProductType.DRINK,
                        BigDecimal.TEN, "@SUM(A1:A9)", true),
                new ProductExportRow("-50% combo", "Đồ uống", ProductType.DRINK, BigDecimal.TEN, "bình thường", true)));

        try (ExcelFile file = service.export(null, null, null, null); XSSFWorkbook workbook = reopen(file)) {
            Row first = workbook.getSheetAt(0).getRow(1);
            for (int column : new int[]{0, 1, 4}) {
                assertThat(first.getCell(column).getCellType()).isEqualTo(CellType.STRING);
                assertThat(first.getCell(column).getCellStyle().getQuotePrefixed()).as("column %d", column).isTrue();
            }
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("=HYPERLINK(\"http://evil.example\")");
            Row second = workbook.getSheetAt(0).getRow(2);
            assertThat(second.getCell(0).getStringCellValue()).isEqualTo("-50% combo");
            assertThat(second.getCell(0).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(second.getCell(4).getCellStyle().getQuotePrefixed()).isFalse();
        }
    }

    @Test
    void exportedFile_isReadBackByTheImportReader_cellForCell() {
        // Hợp đồng "export rồi import lại được": cùng cột, cùng cách ghi giá trị — kể cả tên trông như công thức
        when(productService.findExportRows(null, null, null, null, MAX_ROWS)).thenReturn(List.of(
                new ProductExportRow("-50% combo", "Đồ uống", ProductType.DRINK, new BigDecimal("35000.50"),
                        "Dòng 1\nDòng 2", true),
                new ProductExportRow("Cơm tấm", null, ProductType.FOOD, new BigDecimal("45000.00"), null, false)));

        List<ProductImportRow> read;
        try (ExcelFile file = service.export(null, null, null, null)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            file.writeTo(out);
            read = new ProductImportReader(ExcelTestFiles.properties())
                    .read(ExcelTestFiles.write(directory, out.toByteArray()));
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThat(read).containsExactly(
                new ProductImportRow(2, "-50% combo", "Đồ uống", "Đồ uống", "35000.5", "Dòng 1\nDòng 2", "Có"),
                new ProductImportRow(3, "Cơm tấm", null, "Đồ ăn", "45000", null, "Không"));
    }

    @Test
    void noMatchingProducts_stillProducesAFileWithOnlyTheHeader() throws IOException {
        when(productService.findExportRows(null, null, null, null, MAX_ROWS)).thenReturn(List.of());

        try (ExcelFile file = service.export(null, null, null, null); XSSFWorkbook workbook = reopen(file)) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(file.rowCount()).isZero();
        }
    }

    @Test
    void tooManyRows_propagates_andNothingIsAudited() {
        when(productService.findExportRows(null, null, null, null, MAX_ROWS))
                .thenThrow(new IllegalArgumentException("Số sản phẩm khớp bộ lọc vượt giới hạn 50 dòng"));

        assertThatIllegalArgumentException().isThrownBy(() -> service.export(null, null, null, null))
                .withMessageContaining("vượt giới hạn 50 dòng");
        verifyNoInteractions(auditLogService);
    }

    private static XSSFWorkbook reopen(ExcelFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.writeTo(out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }
}
