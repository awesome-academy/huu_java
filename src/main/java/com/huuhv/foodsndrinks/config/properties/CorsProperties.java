package com.huuhv.foodsndrinks.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Origin được gọi REST API {@code /api/**} từ trình duyệt (CORS). Rỗng (mặc định) = chỉ same-origin.
 * <p>
 * Mỗi giá trị phải là origin đầy đủ {@code scheme://host[:port]} — đúng chuỗi trình duyệt gửi trong header
 * {@code Origin} — nếu không app không khởi động (thay vì lặng lẽ không bao giờ khớp hoặc mở quá rộng):
 * <ul>
 *   <li>không {@code *}/pattern, không {@code null} (origin của iframe sandbox, {@code file://} — ai cũng tạo được);</li>
 *   <li>không path (kể cả {@code /} cuối), query, fragment, user-info;</li>
 *   <li>{@code https}; {@code http} chỉ cho {@code localhost}/{@code 127.0.0.1}/{@code [::1]} (frontend dev).</li>
 * </ul>
 * Scheme/host được viết thường (trình duyệt luôn gửi dạng thường).
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public CorsProperties {
        allowedOrigins = allowedOrigins.stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .map(CorsProperties::validateOrigin)
                .distinct()
                .toList();
    }

    private static String validateOrigin(String origin) {
        if (origin.contains("*")) {
            throw invalid(origin, "'*' is not allowed, list exact origins");
        }
        if (origin.equalsIgnoreCase("null")) {
            throw invalid(origin, "'null' origin is not allowed");
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException ex) {
            throw invalid(origin, "not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw invalid(origin, "must be scheme://host[:port] without path (no trailing '/'), query or user-info");
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK_HOSTS.contains(host))) {
            throw invalid(origin, "must use https (http is only allowed for localhost)");
        }
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static IllegalArgumentException invalid(String origin, String reason) {
        return new IllegalArgumentException(
                "app.cors.allowed-origins (CORS_ALLOWED_ORIGINS) has invalid origin '" + origin + "': " + reason);
    }
}
