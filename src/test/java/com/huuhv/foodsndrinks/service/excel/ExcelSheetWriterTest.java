package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter.Column;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** {@link ExcelSheetWriter}: file ghi ra được đọc lại bằng {@link XSSFWorkbook} để kiểm tra đúng thứ Excel sẽ thấy. */
class ExcelSheetWriterTest {

    private static final List<Column> COLUMNS = List.of(new Column("Tên", 20), new Column("Giá", 12));

    @Test
    void writesHeaderRow_thenDataRowsInOrder() throws IOException {
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sản phẩm", COLUMNS)) {
            writer.row().text("Trà đào").money(new BigDecimal("35000.00"));
            writer.row().text("Bánh mì").money(new BigDecimal("20000"));

            assertThat(writer.dataRowCount()).isEqualTo(2);
            try (XSSFWorkbook workbook = reopen(writer)) {
                Sheet sheet = workbook.getSheetAt(0);
                assertThat(sheet.getSheetName()).isEqualTo("Sản phẩm");
                assertThat(sheet.getLastRowNum()).isEqualTo(2);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Tên");
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Giá");
                assertThat(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold())
                        .isTrue();
                assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Trà đào");
                assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Bánh mì");
                assertThat(sheet.getColumnWidth(0)).isEqualTo(20 * 256);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "=HYPERLINK(\"http://evil.example\",\"x\")", "+84 912 345 678", "-50% combo",
            "@SUM(A1:A9)", "\t=1+1", "\r=1+1", "\n=1+1"})
    void textStartingWithAFormulaTrigger_isQuotePrefixed_andItsValueIsKeptUnchanged(String dangerous) throws IOException {
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            writer.row().text(dangerous);

            try (XSSFWorkbook workbook = reopen(writer)) {
                Cell cell = workbook.getSheetAt(0).getRow(1).getCell(0);
                // Ô chữ (không phải công thức), Excel hiển thị nguyên văn; giá trị không bị chèn thêm dấu '
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                assertThat(cell.getStringCellValue()).isEqualTo(dangerous);
                assertThat(cell.getCellStyle().getQuotePrefixed()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Trà đào", "a=b", "1+1", "user@example.com", "12 Lê Lợi - Quận 1", " =1+1"})
    void ordinaryText_isNotQuotePrefixed(String harmless) throws IOException {
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            writer.row().text(harmless);

            try (XSSFWorkbook workbook = reopen(writer)) {
                Cell cell = workbook.getSheetAt(0).getRow(1).getCell(0);
                assertThat(cell.getStringCellValue()).isEqualTo(harmless);
                assertThat(cell.getCellStyle().getQuotePrefixed()).isFalse();
            }
        }
    }

    @Test
    void numbersAndDates_areRealNumericCells() throws IOException {
        LocalDateTime orderedAt = LocalDateTime.of(2026, 11, 27, 14, 5);
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            writer.row().integer(42).money(new BigDecimal("1250000.00")).dateTime(orderedAt);

            try (XSSFWorkbook workbook = reopen(writer)) {
                Row row = workbook.getSheetAt(0).getRow(1);
                assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(42d);
                assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(1_250_000d);
                assertThat(row.getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
                assertThat(DateUtil.isCellDateFormatted(row.getCell(2))).isTrue();
                assertThat(row.getCell(2).getLocalDateTimeCellValue()).isEqualTo(orderedAt);
            }
        }
    }

    @Test
    void decimal_keepsTheFractionInTheCellValue_soItSurvivesAnImportRoundTrip() throws IOException {
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            writer.row().decimal(new BigDecimal("35000.50")).decimal(new BigDecimal("45000.00")).decimal(null);

            try (XSSFWorkbook workbook = reopen(writer)) {
                Row row = workbook.getSheetAt(0).getRow(1);
                assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(35000.5);
                assertThat(row.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0.##");
                assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(45000d);
                assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.BLANK);
            }
        }
    }

    @Test
    void nullOrEmptyValues_leaveTheCellBlank_andKeepTheColumnPosition() throws IOException {
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            writer.row().text(null).text("").money(null).dateTime(null).text("cuối");

            try (XSSFWorkbook workbook = reopen(writer)) {
                Row row = workbook.getSheetAt(0).getRow(1);
                for (int column = 0; column < 4; column++) {
                    assertThat(row.getCell(column).getCellType()).isEqualTo(CellType.BLANK);
                }
                assertThat(row.getCell(4).getStringCellValue()).isEqualTo("cuối");
            }
        }
    }

    @Test
    void textLongerThanAnExcelCell_isTruncatedInsteadOfFailingTheExport() throws IOException {
        String tooLong = "x".repeat(ExcelSheetWriter.MAX_CELL_LENGTH + 10);
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            writer.row().text(tooLong);

            try (XSSFWorkbook workbook = reopen(writer)) {
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                        .hasSize(ExcelSheetWriter.MAX_CELL_LENGTH);
            }
        }
    }

    @Test
    void manyRows_areStreamedAndAllWritten() throws IOException {
        try (ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS)) {
            // Nhiều hơn cửa sổ dòng giữ trong bộ nhớ của SXSSF
            for (int i = 1; i <= 1_000; i++) {
                writer.row().text("Sản phẩm " + i).integer(i);
            }

            try (XSSFWorkbook workbook = reopen(writer)) {
                Sheet sheet = workbook.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(1_000);
                assertThat(sheet.getRow(1_000).getCell(1).getNumericCellValue()).isEqualTo(1_000d);
            }
        }
    }

    @Test
    void close_canBeCalledMoreThanOnce() {
        // ExcelFile.close() và khối catch của service có thể cùng đóng một writer
        ExcelSheetWriter writer = new ExcelSheetWriter("Sheet", COLUMNS);
        writer.row().text("x");

        assertThatNoException().isThrownBy(() -> {
            writer.close();
            writer.close();
        });
    }

    private static XSSFWorkbook reopen(ExcelSheetWriter writer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }
}
