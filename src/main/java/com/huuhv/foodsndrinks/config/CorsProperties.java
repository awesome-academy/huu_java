package com.huuhv.foodsndrinks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Origin được gọi REST API {@code /api/**} từ trình duyệt (CORS). Rỗng (mặc định) = chỉ same-origin.
 * Phải là origin đầy đủ (vd. {@code https://app.example.com}); không chấp nhận {@code *} hay pattern — app không khởi
 * động nếu cấu hình như vậy.
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException(
                    "app.cors.allowed-origins (CORS_ALLOWED_ORIGINS) must list exact origins, '*' is not allowed");
        }
    }
}
