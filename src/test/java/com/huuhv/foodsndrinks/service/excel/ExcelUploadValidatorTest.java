package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.productWorkbook;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.row;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.upload;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** {@link ExcelUploadValidator}: file bị từ chối trước khi POI mở nó. */
class ExcelUploadValidatorTest {

    private static final byte[] VALID = productWorkbook(row("Trà đào", "Đồ uống", "Đồ uống", 35000, null, "Có"));

    private final ExcelUploadValidator validator = new ExcelUploadValidator(ExcelTestFiles.properties());

    @Test
    void realXlsx_isAccepted_whateverContentTypeTheBrowserSent() {
        assertThatNoException().isThrownBy(() -> validator.validate(upload("san-pham.XLSX", VALID)));
        assertThatNoException().isThrownBy(() ->
                validator.validate(new MockMultipartFile("file", "san-pham.xlsx", "text/plain", VALID)));
    }

    @Test
    void missingOrEmptyFile_isRejected() {
        assertRejected(null, "Vui lòng chọn file Excel (.xlsx)!");
        assertRejected(upload("san-pham.xlsx", new byte[0]), "Vui lòng chọn file Excel (.xlsx)!");
    }

    @Test
    void wrongExtension_isRejected_evenWhenTheContentIsXlsx() {
        assertRejected(upload("san-pham.xls", VALID), "Chỉ nhận file Excel có đuôi .xlsx!");
        assertRejected(upload("san-pham.xlsx.exe", VALID), "Chỉ nhận file Excel có đuôi .xlsx!");
        assertRejected(new MockMultipartFile("file", null, null, VALID), "Chỉ nhận file Excel có đuôi .xlsx!");
    }

    @Test
    void xlsxExtensionWithOtherContent_isRejectedByMagicBytes_notByContentType() {
        // Content-Type "đúng" do trình duyệt gửi không được tin
        assertRejected(upload("san-pham.xlsx", "Tên,Danh mục\nTrà,Đồ uống".getBytes(StandardCharsets.UTF_8)),
                "File không phải file Excel .xlsx hợp lệ!");
        // .xls (OLE2, cũng là định dạng của file Excel đặt mật khẩu) đổi đuôi
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        assertRejected(upload("san-pham.xlsx", ole2), "File không phải file Excel .xlsx hợp lệ!");
        // Chữ ký zip nhưng phần còn lại hỏng
        assertRejected(upload("san-pham.xlsx", new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6, 7, 8}),
                "File không phải file Excel .xlsx hợp lệ!");
    }

    @Test
    void fileLargerThanTheLimit_isRejected() {
        ExcelUploadValidator strict = new ExcelUploadValidator(properties(DataSize.ofBytes(VALID.length - 1),
                DataSize.ofMegabytes(20)));

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> strict.validate(upload("san-pham.xlsx", VALID)))
                .withMessageStartingWith("File vượt quá");
    }

    @Test
    void zipBomb_isRejectedByItsUncompressedSize_beforePoiOpensIt() throws IOException {
        // 4 MB toàn số 0 nén còn vài KB: qua được giới hạn kích thước file, vượt giới hạn sau giải nén (1 MB)
        byte[] bomb = zip("xl/worksheets/sheet1.xml", new byte[4 * 1024 * 1024]);
        ExcelUploadValidator strict = new ExcelUploadValidator(properties(DataSize.ofMegabytes(5),
                DataSize.ofMegabytes(1)));

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> strict.validate(upload("san-pham.xlsx", bomb)))
                .withMessageStartingWith("Nội dung file quá lớn sau khi giải nén");
        // File thật nhỏ hơn giới hạn đó vẫn được nhận
        assertThatNoException().isThrownBy(() -> strict.validate(upload("san-pham.xlsx", VALID)));
    }

    private void assertRejected(MockMultipartFile file, String message) {
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> validator.validate(file))
                .withMessage(message);
    }

    private static ExcelProperties properties(DataSize maxFileSize, DataSize maxUncompressedSize) {
        return new ExcelProperties(new ExcelProperties.Export(50_000),
                new ExcelProperties.Import(1_000, maxFileSize, maxUncompressedSize, 200));
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
