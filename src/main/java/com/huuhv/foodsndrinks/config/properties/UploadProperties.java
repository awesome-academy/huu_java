package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Thư mục lưu ảnh upload ({@code app.upload.dir}) — một nguồn cho nơi ghi ({@code FileStorageService}) và nơi phục vụ
 * {@code /uploads/**} ({@code WebMvcConfig}).
 *
 * @param dir thư mục gốc (tương đối thì tính từ thư mục làm việc của JVM)
 */
@Validated
@ConfigurationProperties("app.upload")
public record UploadProperties(@NotBlank @DefaultValue("./uploads") String dir) {

    /** Đường dẫn tuyệt đối đã chuẩn hoá. */
    public Path rootLocation() {
        return Path.of(dir).toAbsolutePath().normalize();
    }
}
