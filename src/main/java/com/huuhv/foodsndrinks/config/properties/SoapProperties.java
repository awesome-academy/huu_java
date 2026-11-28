package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * SOAP (Spring-WS) — {@code config/WebServiceConfig}, filter chain {@code /ws/**} trong {@code SecurityConfig}.
 *
 * @param maxRequestSize kích thước tối đa của body một request tới {@code /ws/**}. Mọi tài khoản (ai cũng tự đăng ký
 *                       được) gọi được SOAP, và Tomcat không giới hạn body của request không phải form — không chặn
 *                       thì một request có thể bắt server parse hàng trăm MB XML. Request hợp lệ của service này chỉ
 *                       vài trăm byte.
 */
@Validated
@ConfigurationProperties("app.soap")
public record SoapProperties(@NotNull @DefaultValue("64KB") DataSize maxRequestSize) {

    /**
     * Đường dẫn của {@code MessageDispatcherServlet} — phải trùng {@code spring.webservices.path} trong
     * {@code application.yml} ({@code WebServiceConfigTest} kiểm tra). Khác {@code /ws-stomp} (WebSocket).
     */
    public static final String PATH = "/ws";

    public SoapProperties {
        if (maxRequestSize != null && (maxRequestSize.toBytes() < 1024 || maxRequestSize.toBytes() > 1024 * 1024)) {
            throw new IllegalArgumentException("app.soap.max-request-size must be between 1KB and 1MB");
        }
    }
}
