package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/**
 * Kiểm tra một file upload trước khi POI mở nó: có file, đuôi {@code .xlsx}, kích thước, magic bytes (không tin
 * {@code Content-Type} của trình duyệt), và tổng dung lượng sau khi giải nén. Một file .xlsx là file zip: 5 MB nén có
 * thể bung thành hàng trăm MB XML (zip bomb) — bước duyệt trước đếm số byte THỰC SỰ giải nén ra và dừng ngay khi vượt
 * giới hạn, nên file như vậy bị từ chối mà không tốn bộ nhớ. Giới hạn mặc định của POI ({@code ZipSecureFile}) vẫn giữ
 * nguyên, đây là lớp chặn thêm phía trước.
 */
@Component
@RequiredArgsConstructor
public class ExcelUploadValidator {

    static final String EXTENSION = ".xlsx";
    /** Số entry tối đa trong file zip — một workbook bình thường chỉ có vài chục entry. */
    static final int MAX_ZIP_ENTRIES = 2_000;

    private static final String NOT_XLSX = "File không phải file Excel .xlsx hợp lệ!";
    private static final int BUFFER_SIZE = 8_192;

    private final ExcelProperties properties;

    /** @throws ExcelFileException kèm thông báo hiển thị cho admin khi file bị từ chối */
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
            requireUncompressedSizeWithin(file, limits.maxUncompressedSize().toBytes());
        } catch (IOException e) {
            // Zip hỏng, entry mã hoá... — không đưa thông điệp của thư viện ra ngoài
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

    private static void requireUncompressedSizeWithin(MultipartFile file, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            while (zip.getNextEntry() != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new ExcelFileException(NOT_XLSX);
                }
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new ExcelFileException("Nội dung file quá lớn sau khi giải nén. Hãy dùng file mẫu và"
                                + " chỉ giữ dữ liệu cần import!");
                    }
                }
            }
        }
        if (entries == 0) {
            throw new ExcelFileException(NOT_XLSX);
        }
    }
}
