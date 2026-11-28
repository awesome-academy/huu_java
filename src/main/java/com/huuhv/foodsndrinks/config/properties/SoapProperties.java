package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

/**
 * SOAP (Spring-WS) — server: {@code config/WebServiceConfig} + filter chain {@code /ws/**} trong {@code SecurityConfig};
 * client tỷ giá: {@code config/WebServiceClientConfig}, {@code service/soap}.
 *
 * @param maxRequestSize kích thước tối đa của body một request tới {@code /ws/**}. Mọi tài khoản (ai cũng tự đăng ký
 *                       được) gọi được SOAP, và Tomcat không giới hạn body của request không phải form — không chặn
 *                       thì một request có thể bắt server parse hàng trăm MB XML. Request hợp lệ của service này chỉ
 *                       vài trăm byte.
 * @param exchangeRate   dịch vụ tỷ giá bên ngoài mà app gọi (SOAP client)
 */
@Validated
@ConfigurationProperties("app.soap")
public record SoapProperties(@NotNull @DefaultValue("64KB") DataSize maxRequestSize,
                             @NotNull @Valid @DefaultValue ExchangeRate exchangeRate) {

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

    /**
     * Dịch vụ tỷ giá SOAP ({@code xsd/exchange-rate.xsd}). URL và tài khoản chỉ đến từ cấu hình (biến môi trường), không
     * bao giờ từ dữ liệu người dùng nhập — app không gọi tới địa chỉ do người dùng chọn (SSRF).
     *
     * @param url               địa chỉ endpoint ({@code SOAP_EXCHANGE_RATE_URL}); để trống = TẮT tính năng tỷ giá (app
     *                          không gọi đi đâu cả). Chỉ {@code http}/{@code https}, phải có host, không kèm
     *                          {@code user:pass@} — sai thì app không khởi động
     * @param username          tài khoản HTTP Basic của dịch vụ ({@code SOAP_EXCHANGE_RATE_USERNAME}); trống = không gửi
     *                          header {@code Authorization}
     * @param password          mật khẩu ({@code SOAP_EXCHANGE_RATE_PASSWORD}) — không bao giờ ghi ra log
     *                          ({@link #toString()} che đi)
     * @param connectTimeout    chờ mở kết nối tối đa
     * @param readTimeout       chờ response tối đa
     * @param cacheTtl          tỷ giá đã lấy được dùng trong bao lâu trước khi job nền lấy lại; quá 2 lần khoảng này mà
     *                          chưa lấy lại được thì coi như không có tỷ giá
     * @param retryAfterFailure sau một lần gọi lỗi, chờ bấy lâu mới thử lại (không dội dịch vụ đang chết, không dội
     *                          bộ đếm đăng nhập sai nếu tài khoản cấu hình sai)
     * @param mockEndpoint      endpoint tỷ giá giả do chính app phục vụ
     */
    public record ExchangeRate(
            @NotNull @DefaultValue("") String url,
            @NotNull @DefaultValue("") String username,
            @NotNull @DefaultValue("") String password,
            @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 30) @DefaultValue("3s") Duration connectTimeout,
            @NotNull @DurationMin(millis = 100) @DurationMax(seconds = 30) @DefaultValue("3s") Duration readTimeout,
            @NotNull @DurationMin(seconds = 1) @DurationMax(hours = 24) @DefaultValue("10m") Duration cacheTtl,
            @NotNull @DurationMin(seconds = 0) @DurationMax(hours = 1) @DefaultValue("1m") Duration retryAfterFailure,
            @NotNull @Valid @DefaultValue MockEndpoint mockEndpoint) {

        /** Tên property bật endpoint mock — dùng trong {@code @ConditionalOnBooleanProperty}. */
        public static final String MOCK_ENDPOINT_ENABLED = "app.soap.exchange-rate.mock-endpoint.enabled";

        public ExchangeRate {
            url = url == null ? "" : url.strip();
            username = username == null ? "" : username.strip();
            if (!url.isEmpty()) {
                requireHttpUrl(url);
            }
        }

        /** Có URL = tính năng tỷ giá được bật. */
        public boolean hasUrl() {
            return !url.isEmpty();
        }

        public boolean hasCredentials() {
            return !username.isEmpty();
        }

        private static void requireHttpUrl(String url) {
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException ex) {
                throw invalid("is not a valid URL");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw invalid("must use http or https");
            }
            if (uri.getHost() == null || uri.getRawUserInfo() != null) {
                // Không lặp lại giá trị trong thông báo: URL kiểu https://user:pass@host mang mật khẩu
                throw invalid("must have a host and must not contain user-info (use the username/password properties)");
            }
        }

        private static IllegalArgumentException invalid(String reason) {
            return new IllegalArgumentException("app.soap.exchange-rate.url (SOAP_EXCHANGE_RATE_URL) " + reason);
        }

        /** Record tự sinh {@code toString} in mọi field — che mật khẩu để không lọt vào log/thông báo lỗi binding. */
        @Override
        public String toString() {
            return "ExchangeRate[url=" + url + ", username=" + username + ", password=" + (password.isEmpty() ? "" : "***")
                    + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", cacheTtl=" + cacheTtl
                    + ", retryAfterFailure=" + retryAfterFailure + ", mockEndpoint=" + mockEndpoint + "]";
        }
    }

    /**
     * @param enabled bật endpoint tỷ giá giả ({@code SOAP_MOCK_ENDPOINT_ENABLED}). Mặc định tắt; profile {@code dev}
     *                (và container {@code app} của compose, vốn chạy profile dev) bật; {@code prod} luôn tắt
     */
    public record MockEndpoint(@DefaultValue("false") boolean enabled) {
    }
}
