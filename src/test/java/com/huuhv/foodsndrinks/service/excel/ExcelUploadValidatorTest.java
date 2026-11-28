package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.productWorkbook;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.row;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;

/** {@link ExcelUploadValidator}: file bị từ chối trước khi POI đọc nội dung của nó. */
class ExcelUploadValidatorTest {

    private static final byte[] VALID = productWorkbook(row("Trà đào", "Đồ uống", "Đồ uống", 35000, null, "Có"));
    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY = 0x06054b50;

    @TempDir Path directory;

    private final ExcelUploadValidator validator = new ExcelUploadValidator(ExcelTestFiles.properties());

    // -------------------------------------------------------
    // Bước 1: trước khi lưu file
    // -------------------------------------------------------

    @Test
    void realXlsx_isAccepted_whateverContentTypeTheBrowserSent() {
        assertThatNoException().isThrownBy(() -> validator.validate(upload("san-pham.XLSX", VALID)));
        assertThatNoException().isThrownBy(() ->
                validator.validate(new MockMultipartFile("file", "san-pham.xlsx", "text/plain", VALID)));
        assertThatNoException().isThrownBy(() -> validator.validateContent(ExcelTestFiles.write(directory, VALID)));
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
                ExcelUploadValidator.NOT_XLSX);
        // .xls (OLE2, cũng là định dạng của file Excel đặt mật khẩu) đổi đuôi
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        assertRejected(upload("san-pham.xlsx", ole2), ExcelUploadValidator.NOT_XLSX);
    }

    @Test
    void fileLargerThanTheLimit_isRejected() {
        ExcelUploadValidator strict = new ExcelUploadValidator(properties(DataSize.ofBytes(VALID.length - 1),
                DataSize.ofMegabytes(20)));

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> strict.validate(upload("san-pham.xlsx", VALID)))
                .withMessageStartingWith("File vượt quá");
    }

    // -------------------------------------------------------
    // Bước 2: nội dung zip của file đã lưu
    // -------------------------------------------------------

    @Test
    void brokenZipAfterAValidSignature_isRejected() {
        assertContentRejected(new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6, 7, 8}, ExcelUploadValidator.NOT_XLSX);
        assertContentRejected("PK\u0003\u0004 nhưng không phải zip".getBytes(StandardCharsets.UTF_8),
                ExcelUploadValidator.NOT_XLSX);
    }

    @Test
    void contentLargerThanTheLimitOnceUncompressed_isRejected() throws IOException {
        // 2 MB không nén được (tỉ lệ ~1:1): vượt giới hạn giải nén 1 MB của validator
        byte[] big = zip("xl/sharedStrings.xml", randomBytes(2 * 1024 * 1024));
        ExcelUploadValidator strict = strictUncompressed();

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> strict.validateContent(ExcelTestFiles.write(directory, big)))
                .withMessage(ExcelUploadValidator.TOO_LARGE_UNCOMPRESSED);
        // File thật nhỏ hơn giới hạn đó vẫn được nhận
        assertThatNoException().isThrownBy(() -> strict.validateContent(ExcelTestFiles.write(directory, VALID)));
    }

    @Test
    void classicZipBomb_isRejected_beforePoiReadsIt() throws IOException {
        // 4 MB toàn số 0 nén còn vài KB: qua được giới hạn kích thước file; bị chặn bởi giới hạn giải nén của
        // validator hoặc giới hạn tỉ lệ nén của POI — cái nào tới trước
        byte[] bomb = zip("xl/worksheets/sheet1.xml", new byte[4 * 1024 * 1024]);

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> strictUncompressed().validateContent(ExcelTestFiles.write(directory, bomb)));
    }

    /**
     * Tấn công vào cách đọc zip: vài byte rác chèn trước entry lớn. Đọc tuần tự từ đầu file ({@code ZipInputStream}, cách
     * kiểm tra cũ) dừng ở byte rác và chỉ thấy entry đầu; central directory — POI đọc theo nó — vẫn trỏ tới entry lớn.
     */
    @Test
    void entryHiddenFromASequentialReader_isStillCounted_likePoiWouldReadIt() throws IOException {
        byte[] hidden = zipWithGapBeforeSecondEntry(randomBytes(2 * 1024 * 1024));
        assertThat(entriesSeenSequentially(hidden)).as("premise: ZipInputStream misses the big entry").isEqualTo(1);

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> strictUncompressed().validateContent(ExcelTestFiles.write(directory, hidden)))
                .withMessage(ExcelUploadValidator.TOO_LARGE_UNCOMPRESSED);
    }

    @Test
    void entryNamesNotInUtf8_doNotCrashTheUpload() throws IOException {
        // Công cụ nén cũ trên Windows: tên entry theo bảng mã cục bộ (Shift_JIS — không phải UTF-8 hợp lệ), không bật
        // cờ UTF-8 — ZipInputStream (cách kiểm tra cũ) ném IllegalArgumentException với tên như vậy
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, Charset.forName("Shift_JIS"))) {
            zip.putNextEntry(new ZipEntry("商品データ.xml"));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] content = out.toByteArray();

        // Không phải lỗi 500: hoặc được nhận (bộ đọc sẽ từ chối vì không phải workbook), hoặc bị từ chối có thông báo
        Throwable uploadStep = catchThrowable(() -> validator.validate(upload("san-pham.xlsx", content)));
        Throwable contentStep = catchThrowable(() -> validator.validateContent(ExcelTestFiles.write(directory, content)));
        assertThat(uploadStep).satisfiesAnyOf(t -> assertThat(t).isNull(),
                t -> assertThat(t).isInstanceOf(ExcelFileException.class));
        assertThat(contentStep).satisfiesAnyOf(t -> assertThat(t).isNull(),
                t -> assertThat(t).isInstanceOf(ExcelFileException.class));
    }

    private void assertRejected(MockMultipartFile file, String message) {
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> validator.validate(file))
                .withMessage(message);
    }

    private void assertContentRejected(byte[] content, String message) {
        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> validator.validateContent(ExcelTestFiles.write(directory, content)))
                .withMessage(message);
    }

    private static ExcelUploadValidator strictUncompressed() {
        return new ExcelUploadValidator(properties(DataSize.ofMegabytes(5), DataSize.ofMegabytes(1)));
    }

    private static ExcelProperties properties(DataSize maxFileSize, DataSize maxUncompressedSize) {
        return new ExcelProperties(new ExcelProperties.Export(50_000),
                ExcelTestFiles.importLimits(1_000, maxFileSize, maxUncompressedSize, "./import-tmp"));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
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

    private static int entriesSeenSequentially(byte[] content) throws IOException {
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            while (zip.getNextEntry() != null) {
                entries++;
            }
        }
        return entries;
    }

    /**
     * Zip hai entry (một entry nhỏ, rồi {@code xl/sharedStrings.xml} chứa {@code secondContent}) với 4 byte rác chèn
     * ngay trước local header của entry thứ hai; offset trong central directory được sửa cho khớp nên file vẫn là zip
     * hợp lệ với mọi trình đọc dựa trên central directory.
     */
    private static byte[] zipWithGapBeforeSecondEntry(byte[] secondContent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(secondContent);
            zip.closeEntry();
        }
        byte[] original = out.toByteArray();
        ByteBuffer source = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = original.length - 22; // không có comment
        assertThat(source.getInt(eocd)).isEqualTo(END_OF_CENTRAL_DIRECTORY);
        int centralDirectory = source.getInt(eocd + 16);
        // Local header của entry thứ hai = offset ghi trong bản ghi central directory thứ hai
        int firstRecordLength = 46 + u16(source, centralDirectory + 28) + u16(source, centralDirectory + 30)
                + u16(source, centralDirectory + 32);
        int secondLocalHeader = source.getInt(centralDirectory + firstRecordLength + 42);
        assertThat(source.getInt(secondLocalHeader)).isEqualTo(LOCAL_HEADER);

        int gap = 4;
        byte[] patched = new byte[original.length + gap];
        System.arraycopy(original, 0, patched, 0, secondLocalHeader);
        System.arraycopy(original, secondLocalHeader, patched, secondLocalHeader + gap,
                original.length - secondLocalHeader);
        ByteBuffer target = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN);
        int movedCentralDirectory = centralDirectory + gap;
        int count = u16(target, patched.length - 22 + 10);
        for (int i = 0, record = movedCentralDirectory; i < count; i++) {
            assertThat(target.getInt(record)).isEqualTo(CENTRAL_HEADER);
            int localHeader = target.getInt(record + 42);
            if (localHeader >= secondLocalHeader) {
                target.putInt(record + 42, localHeader + gap);
            }
            record += 46 + u16(target, record + 28) + u16(target, record + 30) + u16(target, record + 32);
        }
        target.putInt(patched.length - 22 + 16, movedCentralDirectory);
        return patched;
    }

    private static int u16(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }
}
