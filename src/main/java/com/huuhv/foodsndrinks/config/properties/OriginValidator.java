package com.huuhv.foodsndrinks.config.properties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kiểm tra danh sách origin được phép trong cấu hình (CORS của {@code /api/**}, handshake WebSocket). Mỗi giá trị phải
 * là origin đầy đủ {@code scheme://host[:port]} — đúng chuỗi trình duyệt gửi trong header {@code Origin} — nếu không app
 * không khởi động (thay vì lặng lẽ không bao giờ khớp hoặc mở quá rộng):
 * <ul>
 *   <li>không {@code *}/pattern, không {@code null} (origin của iframe sandbox, {@code file://} — ai cũng tạo được);</li>
 *   <li>không path (kể cả {@code /} cuối), query, fragment, user-info;</li>
 *   <li>{@code https}; {@code http} chỉ cho {@code localhost}/{@code 127.0.0.1}/{@code [::1]} (dev).</li>
 * </ul>
 * Scheme/host được viết thường (trình duyệt luôn gửi dạng thường); giá trị rỗng bị bỏ, trùng lặp bị gộp.
 */
final class OriginValidator {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private OriginValidator() {
    }

    /**
     * @param origins  giá trị cấu hình (đã tách theo dấu phẩy)
     * @param property tên property + biến môi trường đưa vào thông báo lỗi, vd. {@code app.cors.allowed-origins
     *                 (CORS_ALLOWED_ORIGINS)}
     * @return origin đã chuẩn hoá
     */
    static List<String> normalize(List<String> origins, String property) {
        return origins.stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .map(origin -> validate(origin, property))
                .distinct()
                .toList();
    }

    private static String validate(String origin, String property) {
        if (origin.contains("*")) {
            throw invalid(property, origin, "'*' is not allowed, list exact origins");
        }
        if (origin.equalsIgnoreCase("null")) {
            throw invalid(property, origin, "'null' origin is not allowed");
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException ex) {
            throw invalid(property, origin, "not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw invalid(property, origin, "must be scheme://host[:port] without path (no trailing '/'), query or user-info");
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK_HOSTS.contains(host))) {
            throw invalid(property, origin, "must use https (http is only allowed for localhost)");
        }
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static IllegalArgumentException invalid(String property, String origin, String reason) {
        return new IllegalArgumentException(property + " has invalid origin '" + origin + "': " + reason);
    }
}
