package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Kiểm tra một file upload trước khi POI đọc nội dung của nó, theo hai bước:
 * <ol>
 *   <li>{@link #validate(MultipartFile)} — rẻ, trước khi lưu file: có file, đuôi {@code .xlsx}, kích thước, magic bytes
 *       (không tin {@code Content-Type} của trình duyệt).</li>
 *   <li>{@link #validateContent(Path)} — trên file đã lưu ra đĩa: tổng dung lượng sau khi giải nén. Một file .xlsx là file
 *       zip: 5 MB nén có thể bung thành hàng trăm MB XML (zip bomb). Bước này giải nén từng entry và đếm số byte THỰC SỰ
 *       đọc ra, dừng ngay khi vượt giới hạn.</li>
 * </ol>
 * Bước 2 đọc file bằng chính {@link ZipSecureFile} — lớp mà POI dùng khi mở file ({@code OPCPackage.open(File)}): cùng
 * cách tìm entry (theo central directory ở cuối file) và cùng các giới hạn mặc định của POI (tỉ lệ nén, kích thước entry,
 * số entry). Không đếm bằng {@code ZipInputStream}: nó đọc tuần tự các local header từ đầu file và dừng ở chữ ký lạ đầu
 * tiên, nên một file tự chế (chèn vài byte rác trước entry lớn) cho nó thấy một tập entry khác với tập POI sẽ đọc.
 */
@Component
@RequiredArgsConstructor
public class ExcelUploadValidator {

    static final String EXTENSION = ".xlsx";
    /** Số entry tối đa trong file zip — một workbook bình thường chỉ có vài chục entry. */
    static final int MAX_ZIP_ENTRIES = 2_000;
    static final String NOT_XLSX = "File không phải file Excel .xlsx hợp lệ!";
    static final String TOO_LARGE_UNCOMPRESSED = "Nội dung file quá lớn sau khi giải nén. Hãy dùng file mẫu và chỉ"
            + " giữ dữ liệu cần import!";

    private static final int BUFFER_SIZE = 8_192;

    private final ExcelProperties properties;

    /**
     * Bước 1 (trước khi lưu file).
     *
     * @throws ExcelFileException kèm thông báo hiển thị cho admin khi file bị từ chối
     */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ExcelFileException("Vui lòng chọn file Excel (.xlsx)!");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            throw new ExcelFileException("Chỉ nhận file Excel có đuôi .xlsx!");
        }
        ExcelProperties.Import limits = properties.imports();
        if (file.getSize() > limits.maxFileSize().toBytes()) {
            throw new ExcelFileException("File vượt quá " + limits.maxFileSize().toMegabytes() + " MB!");
        }
        try {
            requireZipSignature(file);
        } catch (ExcelFileException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Không đưa thông điệp của thư viện ra ngoài
            throw new ExcelFileException(NOT_XLSX, e);
        }
    }

    /**
     * Bước 2 (file đã lưu ra đĩa, trước khi tạo lần import).
     *
     * @throws ExcelFileException file không phải zip đọc được, có quá nhiều entry, hoặc quá
     *                            {@code app.excel.import.max-uncompressed-size} sau khi giải nén
     */
    public void validateContent(Path file) {
        long maxBytes = properties.imports().maxUncompressedSize().toBytes();
        try (ZipSecureFile zip = new ZipSecureFile(file.toFile())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int entries = 0;
            for (Enumeration<ZipArchiveEntry> all = zip.getEntries(); all.hasMoreElements(); ) {
                ZipArchiveEntry entry = all.nextElement();
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new ExcelFileException(NOT_XLSX);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                // Luồng có kiểm tra tỉ lệ nén của POI (ZipArchiveThresholdInputStream) — như lúc POI đọc thật
                try (InputStream in = zip.getInputStream(entry)) {
                    if (in == null) {
                        throw new ExcelFileException(NOT_XLSX);
                    }
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            throw new ExcelFileException(TOO_LARGE_UNCOMPRESSED);
                        }
                    }
                }
            }
            if (entries == 0) {
                throw new ExcelFileException(NOT_XLSX);
            }
        } catch (ExcelFileException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Zip hỏng, entry mã hoá, tỉ lệ nén bất thường (giới hạn của POI)... — không đưa thông điệp thư viện ra ngoài
            throw new ExcelFileException(NOT_XLSX, e);
        }
    }

    /** .xlsx (OOXML) bắt đầu bằng chữ ký zip; file Excel đặt mật khẩu hoặc .xls đổi đuôi là OLE2 → bị từ chối ở đây. */
    private static void requireZipSignature(MultipartFile file) throws IOException {
        try (InputStream in = FileMagic.prepareToCheckMagic(file.getInputStream())) {
            if (FileMagic.valueOf(in) != FileMagic.OOXML) {
                throw new ExcelFileException(NOT_XLSX);
            }
        }
    }
}
