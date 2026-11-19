package com.huuhv.foodsndrinks.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {

    private final Path rootLocation;

    public FileStorageService(@Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tạo thư mục upload: " + rootLocation, e);
        }
    }

    /**
     * Saves a file under the given sub-folder and returns the URL path (e.g. /uploads/products/uuid.jpg).
     * <p>
     * Chỉ nhận PNG/JPEG/GIF/WEBP: đuôi file phải thuộc danh sách VÀ nội dung (magic bytes) phải đúng định dạng của đuôi
     * đó. {@code Content-Type} do client gửi lên không được dùng (giả mạo được). Tên file lưu là UUID + đuôi chuẩn hoá.
     */
    public String store(MultipartFile file, String subFolder) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File không được để trống!");
        }

        String originalName = file.getOriginalFilename();
        String extension    = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.'))
                : "";
        ImageFormat format = ImageFormat.fromExtension(extension)
                .orElseThrow(() -> new IllegalArgumentException("Định dạng ảnh không hợp lệ!"));
        if (!format.matches(readHeader(file))) {
            log.warn("Rejected upload: content does not match extension {}", format);
            throw new IllegalArgumentException("Nội dung file không phải ảnh " + format.name() + " hợp lệ!");
        }
        String fileName = UUID.randomUUID() + format.storedExtension();

        try {
            Path destination = rootLocation.resolve(subFolder).normalize();
            if (!destination.startsWith(rootLocation)) {
                throw new IllegalArgumentException("Thư mục upload không hợp lệ!");
            }
            Files.createDirectories(destination);
            file.transferTo(destination.resolve(fileName));
        } catch (IOException e) {
            throw new IllegalStateException("Không thể lưu file: " + fileName, e);
        }

        return "/uploads/" + subFolder + "/" + fileName;
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageFormat.HEADER_LENGTH);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể đọc file upload", e);
        }
    }

    /**
     * Deletes a file given its URL path (e.g. /uploads/products/uuid.jpg).
     */
    public void delete(String urlPath) {
        if (urlPath == null || urlPath.isBlank()) return;
        try {
            // Strip the leading /uploads/ prefix to get the relative path
            String relative = urlPath.startsWith("/uploads/") ? urlPath.substring("/uploads/".length()) : urlPath;
            Path target = rootLocation.resolve(relative).normalize();
            if (!target.startsWith(rootLocation)) {
                log.warn("Từ chối xóa file ngoài thư mục upload: {}", urlPath);
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Không thể xóa file: {}", urlPath, e);
        }
    }
}


