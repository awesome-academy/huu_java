package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Dựng file .xlsx và cấu hình Excel cho test — file được tạo bằng POI thật, đúng thứ Excel ghi ra. */
public final class ExcelTestFiles {

    public static final String XLSX_CONTENT_TYPE = ExcelSheetWriter.XLSX_CONTENT_TYPE;
    public static final List<String> PRODUCT_HEADERS = ProductExcelColumns.HEADERS;

    private ExcelTestFiles() {
    }

    /** Cấu hình mặc định như {@code application.yml}. */
    public static ExcelProperties properties() {
        return properties(50_000, 1_000);
    }

    public static ExcelProperties properties(int exportMaxRows, int importMaxRows) {
        return new ExcelProperties(new ExcelProperties.Export(exportMaxRows),
                importLimits(importMaxRows, DataSize.ofMegabytes(5), DataSize.ofMegabytes(20), "./import-tmp"));
    }

    /** Cấu hình mặc định, file import nằm trong {@code importDirectory}. */
    public static ExcelProperties propertiesWithImportDir(Path importDirectory) {
        return new ExcelProperties(new ExcelProperties.Export(50_000),
                importLimits(1_000, DataSize.ofMegabytes(5), DataSize.ofMegabytes(20), importDirectory.toString()));
    }

    public static ExcelProperties.Import importLimits(int maxRows, DataSize maxFileSize, DataSize maxUncompressedSize,
                                                      String directory) {
        return new ExcelProperties.Import(maxRows, maxFileSize, maxUncompressedSize, 200, directory, 3,
                Duration.ofMinutes(30));
    }

    /**
     * File sản phẩm theo mẫu: dòng tiêu đề chuẩn + các dòng dữ liệu. Mỗi ô: {@code String} → ô chữ, {@link Number} → ô
     * số, {@link Boolean} → ô boolean, {@code null} → không có ô.
     */
    public static byte[] productWorkbook(Object[]... dataRows) {
        return workbook(PRODUCT_HEADERS.toArray(), dataRows);
    }

    /** File có dòng đầu tiên tuỳ ý (để thử sai mẫu). */
    public static byte[] workbook(Object[] headerRow, Object[]... dataRows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sản phẩm");
            writeRow(sheet.createRow(0), headerRow);
            for (int i = 0; i < dataRows.length; i++) {
                if (dataRows[i] != null) {
                    writeRow(sheet.createRow(i + 1), dataRows[i]);
                }
            }
            return bytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    public static Path write(Path directory, byte[] content) {
        try {
            return Files.write(Files.createTempFile(directory, "import-", ".xlsx"), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MockMultipartFile upload(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, XLSX_CONTENT_TYPE, content);
    }

    public static Object[] row(Object... cells) {
        return cells;
    }

    private static void writeRow(Row row, Object[] cells) {
        for (int column = 0; column < cells.length; column++) {
            switch (cells[column]) {
                case null -> { }
                case String text -> row.createCell(column).setCellValue(text);
                case BigDecimal number -> row.createCell(column).setCellValue(number.doubleValue());
                case Number number -> row.createCell(column).setCellValue(number.doubleValue());
                case Boolean flag -> row.createCell(column).setCellValue(flag);
                default -> throw new IllegalArgumentException("Unsupported cell value: " + cells[column].getClass());
            }
        }
    }
}
