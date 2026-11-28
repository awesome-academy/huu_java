package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.exception.ExcelFileException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.productWorkbook;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** {@link ProductImportReader}: đọc file .xlsx thật (dựng bằng POI) từ đĩa. */
class ProductImportReaderTest {

    @TempDir Path directory;

    private final ProductImportReader reader = new ProductImportReader(ExcelTestFiles.properties());

    @Test
    void readsDataRows_asRawStrings_withExcelRowNumbers() {
        List<ProductImportRow> rows = read(productWorkbook(
                row("Trà đào", "Đồ uống", "Đồ uống", 35000, "Trà đào cam sả", "Có"),
                row("  Bánh mì  ", "Đồ ăn", "FOOD", 20000.5, null, true)));

        assertThat(rows).containsExactly(
                // Ô số trả về số thô, không theo định dạng hiển thị; ô trống là null; khoảng trắng giữ nguyên (validator cắt)
                new ProductImportRow(2, "Trà đào", "Đồ uống", "Đồ uống", "35000", "Trà đào cam sả", "Có"),
                new ProductImportRow(3, "  Bánh mì  ", "Đồ ăn", "FOOD", "20000.5", null, "TRUE"));
    }

    @Test
    void numericCellWithCurrencyFormat_isReadAsThePlainNumber() throws IOException {
        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            header(sheet);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Trà đào");
            var price = row.createCell(3);
            price.setCellValue(1_250_000);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("#,##0 \"₫\""));
            price.setCellStyle(style);
            file = ExcelTestFiles.bytes(workbook);
        }

        assertThat(read(file)).singleElement().extracting(ProductImportRow::price).isEqualTo("1250000");
    }

    @Test
    void formulaCell_yieldsItsStoredValue_andIsNeverEvaluated() throws IOException {
        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            header(sheet);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Trà đào");
            // Không có giá trị lưu sẵn (file do công cụ tạo, chưa từng được Excel tính) → ô coi như trống
            row.createCell(3).setCellFormula("1+1");
            file = ExcelTestFiles.bytes(workbook);
        }

        assertThat(read(file)).singleElement().extracting(ProductImportRow::price).isNotEqualTo("2");
    }

    @Test
    void blankRows_areSkipped_andKeepTheRowNumbersOfTheFile() {
        List<ProductImportRow> rows = read(productWorkbook(
                row("A", "Đồ ăn", "FOOD", 1000, null, null),
                null,
                row("   ", null, null, null, null, " "),
                row("B", "Đồ ăn", "FOOD", 2000, null, null)));

        assertThat(rows).extracting(ProductImportRow::rowNumber).containsExactly(2, 5);
        assertThat(rows).extracting(ProductImportRow::name).containsExactly("A", "B");
    }

    @Test
    void cellsBeyondTheSixTemplateColumns_areIgnoredInDataRows() {
        assertThat(read(productWorkbook(row("A", "Đồ ăn", "FOOD", 1000, null, "Có", "ghi chú riêng"))))
                .containsExactly(new ProductImportRow(2, "A", "Đồ ăn", "FOOD", "1000", null, "Có"));
    }

    @Test
    void headerIsMatchedIgnoringCaseAndSurroundingSpaces() {
        byte[] file = ExcelTestFiles.workbook(
                row(" tên ", "DANH MỤC", "Loại", "giá", "Mô tả", "Còn bán"),
                row("A", "Đồ ăn", "FOOD", 1000, null, null));

        assertThat(read(file)).hasSize(1);
    }

    @Test
    void wrongHeader_isRejected_asNotTheTemplate() {
        // Thiếu cột, sai thứ tự, thừa cột, không có dòng tiêu đề
        assertWrongTemplate(ExcelTestFiles.workbook(row("Tên", "Danh mục", "Loại", "Giá", "Mô tả"),
                row("A", "Đồ ăn", "FOOD", 1000)));
        assertWrongTemplate(ExcelTestFiles.workbook(row("Tên", "Loại", "Danh mục", "Giá", "Mô tả", "Còn bán"),
                row("A", "FOOD", "Đồ ăn", 1000)));
        assertWrongTemplate(ExcelTestFiles.workbook(row("Tên", "Danh mục", "Loại", "Giá", "Mô tả", "Còn bán", "Ảnh"),
                row("A", "Đồ ăn", "FOOD", 1000)));
        assertWrongTemplate(ExcelTestFiles.workbook(row(), row("A", "Đồ ăn", "FOOD", 1000)));
    }

    @Test
    void headerOnly_isRejected_asHavingNoData() {
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> read(productWorkbook()))
                .withMessage("File không có dòng dữ liệu nào!");
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> read(productWorkbook(row(" ", null, null))))
                .withMessage("File không có dòng dữ liệu nào!");
    }

    @Test
    void moreRowsThanTheLimit_isRejected() {
        ProductImportReader limited = new ProductImportReader(ExcelTestFiles.properties(50_000, 3));
        Object[][] three = {row("A", "x", "FOOD", 1), row("B", "x", "FOOD", 1), row("C", "x", "FOOD", 1)};
        Object[][] four = {three[0], three[1], three[2], row("D", "x", "FOOD", 1)};

        assertThat(limited.read(ExcelTestFiles.write(directory, productWorkbook(three)))).hasSize(3);
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> limited.read(ExcelTestFiles.write(directory, productWorkbook(four))))
                .withMessageStartingWith("File có nhiều hơn 3 dòng dữ liệu");
    }

    @Test
    void onlyTheFirstSheetIsRead() throws IOException {
        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet first = workbook.createSheet("Sản phẩm");
            header(first);
            first.createRow(1).createCell(0).setCellValue("Từ sheet đầu");
            Sheet second = workbook.createSheet("Hướng dẫn");
            second.createRow(0).createCell(0).setCellValue("Cột");
            second.createRow(1).createCell(0).setCellValue("Từ sheet hai");
            file = ExcelTestFiles.bytes(workbook);
        }

        assertThat(read(file)).extracting(ProductImportRow::name).containsExactly("Từ sheet đầu");
    }

    @Test
    void fileThatIsNotAWorkbook_isRejected_withoutLeakingLibraryMessages() throws IOException {
        Path notExcel = Files.write(directory.resolve("fake.xlsx"), "not a zip at all".getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> reader.read(notExcel))
                .withMessage("Không đọc được file Excel. Hãy tải file mẫu, nhập dữ liệu rồi thử lại!");
    }

    @Test
    void textThatIsNotValidUtf8InsideTheWorkbook_isAFileError_notASystemError() throws IOException {
        // Byte 0xE0 đứng một mình: không phải UTF-8 hợp lệ — parser XML báo bằng một IOException (CharConversionException)
        // hoặc SAXParseException; cả hai đều là lỗi của file, giao lại cũng hỏng y vậy
        byte[] broken = withEntry(productWorkbook(row("Trà đào", "Đồ uống", "Đồ uống", 35000, null, "Có")),
                "xl/sharedStrings.xml", xml -> replace(xml, "Trà đào", new byte[]{'T', 'r', (byte) 0xE0, ' '}));

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> read(broken))
                .withMessageStartingWith("Không đọc được file Excel");
    }

    @Test
    void ioErrorWhileReadingTheFile_isASystemError_soTheMessageIsRedelivered_notBlamedOnTheFile() {
        // File biến mất / volume mạng lỗi giữa chừng: không phải lỗi của file admin tải lên
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> reader.read(directory.resolve("missing.xlsx")));
    }

    @Test
    void phoneticGuideOfJapaneseText_isNotAppendedToTheCellValue() throws IOException {
        // IME tiếng Nhật lưu phần phiên âm (furigana) cạnh chữ trong sharedStrings: <t>寿司 Sushi</t><rPh>…スシ…</rPh>
        byte[] withPhonetics = withEntry(productWorkbook(row("寿司 Sushi", "Đồ ăn", "Đồ ăn", 90000, null, "Có")),
                "xl/sharedStrings.xml", xml -> replace(xml, "<t>寿司 Sushi</t>",
                        "<t>寿司 Sushi</t><rPh sb=\"0\" eb=\"2\"><t>スシ</t></rPh>".getBytes(StandardCharsets.UTF_8)));
        Path file = ExcelTestFiles.write(directory, withPhonetics);
        // Tiền đề: bảng sharedStrings của POI mặc định nối phần phiên âm vào giá trị
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            ReadOnlySharedStringsTable withDefaults = new ReadOnlySharedStringsTable(pkg);
            List<String> strings = new java.util.ArrayList<>();
            for (int i = 0; i < withDefaults.getUniqueCount(); i++) {
                strings.add(withDefaults.getItemAt(i).getString());
            }
            assertThat(strings).contains("寿司 Sushi スシ");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThat(reader.read(file)).singleElement().extracting(ProductImportRow::name).isEqualTo("寿司 Sushi");
    }

    @Test
    void templateProducedByTheApp_isReadableOnceFilled() throws IOException {
        // Đọc được chính dòng tiêu đề mà ProductExcelColumns định nghĩa cho file mẫu/export
        assertThat(read(productWorkbook(row("A", "Đồ ăn", "Đồ ăn", 1000, "mô tả", "Không"))))
                .singleElement().extracting(ProductImportRow::available).isEqualTo("Không");
    }

    private List<ProductImportRow> read(byte[] content) {
        return reader.read(ExcelTestFiles.write(directory, content));
    }

    private void assertWrongTemplate(byte[] content) {
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> read(content))
                .withMessageStartingWith("File không đúng mẫu");
    }

    /** Chép lại file zip, thay nội dung của một entry. */
    private static byte[] withEntry(byte[] xlsx, String entryName, UnaryOperator<byte[]> change) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean found = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals(entryName)) {
                    content = change.apply(content);
                    found = true;
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        }
        assertThat(found).as("entry %s", entryName).isTrue();
        return out.toByteArray();
    }

    private static byte[] replace(byte[] content, String target, byte[] replacement) {
        byte[] wanted = target.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + wanted.length <= content.length; i++) {
            if (Arrays.equals(content, i, i + wanted.length, wanted, 0, wanted.length)) {
                byte[] result = new byte[content.length - wanted.length + replacement.length];
                System.arraycopy(content, 0, result, 0, i);
                System.arraycopy(replacement, 0, result, i, replacement.length);
                System.arraycopy(content, i + wanted.length, result, i + replacement.length,
                        content.length - i - wanted.length);
                return result;
            }
        }
        throw new AssertionError("\"" + target + "\" not found");
    }

    private static void header(Sheet sheet) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < ExcelTestFiles.PRODUCT_HEADERS.size(); i++) {
            header.createCell(i).setCellValue(ExcelTestFiles.PRODUCT_HEADERS.get(i));
        }
    }
}
