package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.springframework.boot.artemis.autoconfigure.ArtemisConfigurationCustomizer;
import org.springframework.boot.artemis.autoconfigure.ArtemisMode;
import org.springframework.boot.artemis.autoconfigure.ArtemisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Broker Artemis embedded (dev/test). Spring Boot 4 tạo broker embedded khi có {@code artemis-jakarta-server} trên
 * classpath và {@code spring.artemis.embedded.enabled=true} — <b>không xét</b> {@code spring.artemis.mode}. Vì vậy prod
 * và container app của compose (broker riêng, {@code mode=native}) phải đặt cả {@code embedded.enabled=false}; quên một
 * trong hai biến thì app không khởi động (thay vì lặng lẽ chạy thêm một broker không ai dùng).
 * <p>
 * Retry + dead-letter khai báo một lần trong file broker properties của Artemis ({@code artemis/broker.properties}, dùng
 * chung với broker riêng — compose nạp bằng {@code artemis run --properties}). Customizer bên dưới (Boot chỉ gọi khi
 * broker embedded được tạo) áp lần lượt các file trong {@code app.jms.embedded-broker-properties}. Parser của Artemis gặp
 * khoá sai <b>không ném lỗi</b>, chỉ ghi vào {@link ConfigurationImpl#getStatus()}; nên sau khi parse phải đọc status:
 * có lỗi, file rỗng hoặc status không đúng định dạng đã biết (có thể đổi khi nâng Artemis) → app không khởi động.
 */
@Slf4j
@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties(JmsBrokerProperties.class)
public class ArtemisEmbeddedConfig {

    @Bean
    public ArtemisConfigurationCustomizer brokerPropertiesCustomizer(ArtemisProperties artemisProperties,
                                                                     JmsBrokerProperties jmsBrokerProperties,
                                                                     ResourceLoader resourceLoader,
                                                                     JsonMapper jsonMapper) {
        return configuration -> {
            if (artemisProperties.getMode() == ArtemisMode.NATIVE) {
                throw new IllegalStateException("Embedded Artemis broker is starting although spring.artemis.mode=native:"
                        + " set spring.artemis.embedded.enabled=false (ARTEMIS_EMBEDDED_ENABLED=false) too");
            }
            for (String location : jmsBrokerProperties.embeddedBrokerProperties()) {
                applyBrokerProperties(configuration, resourceLoader.getResource(location), location, jsonMapper);
            }
        };
    }

    static void applyBrokerProperties(Configuration configuration, Resource resource, String location,
                                      JsonMapper jsonMapper) {
        if (!(configuration instanceof ConfigurationImpl impl)) {
            throw new IllegalStateException("Unsupported Artemis configuration type " + configuration.getClass().getName());
        }
        Properties properties = load(resource, location);
        if (properties.isEmpty()) {
            throw new IllegalStateException("Artemis broker properties " + location + " has no keys");
        }
        // Tên nguồn trong status: Artemis tách theo "/" thành object lồng nhau → dùng tên file, không dùng cả location
        String source = statusSourceName(resource, location);
        try {
            impl.parsePrefixedProperties(impl, source, properties, null);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot apply Artemis broker properties " + location, ex);
        }
        List<String> errors = errors(impl.getStatus(), source, jsonMapper);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid Artemis broker properties " + location + ": " + errors);
        }
        log.info("Applied {} Artemis broker properties from {}", properties.size(), location);
    }

    static String statusSourceName(Resource resource, String location) {
        String filename = resource.getFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalStateException("Artemis broker properties location must name a file: " + location);
        }
        return filename;
    }

    private static Properties load(Resource resource, String location) {
        if (!resource.exists()) {
            throw new IllegalStateException("Artemis broker properties not found: " + location);
        }
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read Artemis broker properties " + location, ex);
        }
        return properties;
    }

    /**
     * Lỗi parse của một nguồn trong status JSON của Artemis:
     * {@code {"properties":{"<nguồn>":{"errors":[{"value":"khoá=giá trị","reason":"..."}]}}}}. Định dạng khác → lỗi
     * (fail closed: không chắc file đã được áp đúng).
     */
    static List<String> errors(String status, String source, JsonMapper jsonMapper) {
        JsonNode errors;
        try {
            errors = jsonMapper.readTree(status == null ? "" : status).path("properties").path(source).path("errors");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Unrecognized Artemis configuration status after applying " + source, ex);
        }
        if (!errors.isArray()) {
            throw new IllegalStateException("Unrecognized Artemis configuration status after applying " + source
                    + " (no properties." + source + ".errors array)");
        }
        List<String> result = new ArrayList<>();
        // File cấu hình của chính app (không chứa secret) — đưa nguyên khoá + lý do vào thông báo lỗi khởi động
        errors.forEach(error -> result.add(error.path("value").asString("?") + " (" + error.path("reason").asString("?") + ")"));
        return result;
    }
}
