package com.huuhv.foodsndrinks.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Origin được gọi REST API {@code /api/**} từ trình duyệt (CORS). Rỗng (mặc định) = chỉ same-origin.
 * <p>
 * Mỗi giá trị phải là origin đầy đủ {@code scheme://host[:port]}, không {@code *}/{@code null}/path, {@code http} chỉ cho
 * localhost — sai thì app không khởi động (quy tắc ở {@link OriginValidator}, dùng chung với {@link WebSocketProperties}).
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = OriginValidator.normalize(allowedOrigins, "app.cors.allowed-origins (CORS_ALLOWED_ORIGINS)");
    }
}
