package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * Excel import/export (Apache POI): đăng ký {@link ExcelProperties} cho các service trong {@code service/excel} và kiểm
 * tra cấu hình lúc khởi động.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExcelProperties.class)
public class ExcelConfig {

    static final String MULTIPART_MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";
    /** Mặc định của Spring Boot khi không khai báo {@value #MULTIPART_MAX_FILE_SIZE}. */
    private static final DataSize MULTIPART_DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(1);

    /**
     * File lớn hơn giới hạn multipart của servlet bị Tomcat từ chối trước khi tới controller (và trước cả CsrfFilter → 403),
     * nên giới hạn import lớn hơn con số đó là cấu hình không bao giờ có hiệu lực — báo lỗi ngay thay vì để người vận hành
     * tưởng đã nâng được giới hạn.
     */
    public ExcelConfig(ExcelProperties properties, Environment environment) {
        // Đọc dạng chuỗi rồi tự parse: không phụ thuộc Environment có sẵn converter String → DataSize hay không
        String configured = environment.getProperty(MULTIPART_MAX_FILE_SIZE);
        DataSize multipartLimit = configured == null || configured.isBlank()
                ? MULTIPART_DEFAULT_MAX_FILE_SIZE : DataSize.parse(configured.strip());
        DataSize importLimit = properties.imports().maxFileSize();
        // Giá trị âm của multipart = không giới hạn
        if (!multipartLimit.isNegative() && importLimit.compareTo(multipartLimit) > 0) {
            throw new IllegalStateException("app.excel.import.max-file-size (" + importLimit.toBytes()
                    + " bytes) must not exceed " + MULTIPART_MAX_FILE_SIZE + " (" + multipartLimit.toBytes() + " bytes)");
        }
    }
}
