package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.config.properties.UploadProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nơi giữ file .xlsx của các lần import đang chờ xử lý ({@code app.excel.import.dir}).
 * <ul>
 *   <li><b>Không public:</b> thư mục không được nằm trong {@code app.upload.dir} (được phục vụ tại {@code /uploads/**}) —
 *       kiểm tra lúc khởi động — và không có resource handler nào trỏ tới nó.</li>
 *   <li><b>Tên file do server sinh</b> ({@code <UUID>.xlsx}); tên client gửi không bao giờ chạm tới hệ thống file. Mọi tên
 *       nhận vào ({@link #resolve}) phải đúng mẫu đó và đường dẫn kết quả phải nằm trong thư mục gốc (chống path
 *       traversal, kể cả khi giá trị đọc từ DB bị sửa).</li>
 *   <li><b>File tạm:</b> bị xoá khi lần import kết thúc ({@link #delete}); file sót lại (instance chết giữa chừng) được
 *       {@code ImportJobService#deleteOrphanFiles} dọn (liệt kê bằng {@link #listOlderThan}).</li>
 * </ul>
 * Chạy nhiều instance: thư mục phải là volume dùng chung — instance nhận upload và instance xử lý message của queue có
 * thể khác nhau.
 */
@Slf4j
@Component
public class ImportFileStorage {

    private static final Pattern STORED_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.xlsx");

    private final Path root;

    public ImportFileStorage(ExcelProperties excelProperties, UploadProperties uploadProperties) {
        this.root = excelProperties.imports().directory();
        Path publicRoot = uploadProperties.rootLocation();
        if (root.startsWith(publicRoot) || publicRoot.startsWith(root)) {
            throw new IllegalStateException("app.excel.import.dir (" + root + ") must not be inside, or contain, the "
                    + "public upload directory app.upload.dir (" + publicRoot + ")");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create app.excel.import.dir: " + root, e);
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IllegalStateException("app.excel.import.dir is not a writable directory: " + root);
        }
    }

    /**
     * Lưu file upload dưới một tên mới do server sinh.
     *
     * @return tên file đã lưu (không phải đường dẫn) — ghi vào {@code import_jobs.stored_file}
     */
    public String store(MultipartFile upload) throws IOException {
        String name = UUID.randomUUID() + ExcelUploadValidator.EXTENSION;
        Path target = root.resolve(name);
        try (InputStream in = upload.getInputStream()) {
            Files.copy(in, target);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return name;
    }

    /**
     * Đường dẫn của file đã lưu.
     *
     * @throws IllegalArgumentException khi {@code storedName} không phải tên do {@link #store} sinh
     */
    public Path resolve(String storedName) {
        if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
            throw new IllegalArgumentException("Not a stored import file name");
        }
        Path file = root.resolve(storedName).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Not a stored import file name");
        }
        return file;
    }

    public boolean exists(String storedName) {
        return Files.isRegularFile(resolve(storedName));
    }

    /**
     * Xoá file đã lưu; không có file thì thôi. Lỗi chỉ ghi log — file sót lại sẽ được job dọn dẹp xoá sau.
     *
     * @return {@code true} nếu file vừa bị xoá
     */
    public boolean delete(String storedName) {
        try {
            return deleteQuietly(resolve(storedName));
        } catch (IllegalArgumentException e) {
            log.warn("Ignored request to delete a file that is not a stored import file");
            return false;
        }
    }

    /**
     * Tên các file import có lần sửa cuối cũ hơn {@code age} (chỉ file đúng mẫu tên của {@link #store}; file lạ trong thư
     * mục bị bỏ qua). Chỉ liệt kê: có xoá hay không là việc của người gọi (file của lần import chưa kết thúc phải giữ).
     */
    public List<String> listOlderThan(Duration age) {
        Instant threshold = Instant.now().minus(age);
        List<String> old = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*" + ExcelUploadValidator.EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (STORED_NAME.matcher(name).matches()
                        && Files.getLastModifiedTime(file).toInstant().isBefore(threshold)) {
                    old.add(name);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list the import directory", e);
        }
        return old;
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete import file {}", file.getFileName(), e);
            return false;
        }
    }
}
