package com.huuhv.foodsndrinks.utils;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** Thông tin của HTTP request hiện tại (thread đang xử lý request) dùng chung cho audit log và rate limit. */
public final class RequestUtils {

    /** Chỉ nhận literal IP (chữ số, hex, dấu chấm/hai chấm) để {@link InetAddress#getByName} không bao giờ tra DNS. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");
    private static final int IPV6_NETWORK_BYTES = 8;

    private RequestUtils() {
    }

    /**
     * IP client của request hiện tại. Profile prod bật {@code server.forward-headers-strategy=native}: khi request đến
     * từ load balancer thuộc {@code server.tomcat.remoteip.internal-proxies} ({@code TRUSTED_PROXIES}), Tomcat thay
     * {@code getRemoteAddr()} bằng IP client thật lấy từ {@code X-Forwarded-For}; header này từ nguồn khác bị bỏ qua nên
     * client không tự giả được IP. Dev không bật, giá trị là IP kết nối trực tiếp.
     *
     * @return rỗng nếu thread hiện tại không xử lý HTTP request (job, {@code @Async}...)
     */
    public static Optional<String> currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.ofNullable(attrs.getRequest().getRemoteAddr());
        }
        return Optional.empty();
    }

    /**
     * Key giới hạn tần suất theo nguồn: IPv4 giữ nguyên, IPv6 gộp theo mạng /64 — một máy IPv6 thường được cấp cả dải
     * /64, nếu đếm theo từng địa chỉ thì kẻ tấn công đổi địa chỉ để có bộ đếm mới gần như vô hạn.
     */
    public static String rateLimitKey(String ip) {
        if (ip == null || ip.isBlank()) {
            return "-";
        }
        if (ip.indexOf(':') < 0 || !IP_LITERAL.matcher(ip).matches()) {
            return ip;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address instanceof Inet6Address) {
                byte[] network = new byte[IPV6_NETWORK_BYTES];
                System.arraycopy(address.getAddress(), 0, network, 0, IPV6_NETWORK_BYTES);
                return HexFormat.of().formatHex(network) + "::/64";
            }
            // IPv4-mapped IPv6 (::ffff:1.2.3.4) → Inet4Address
            return address.getHostAddress();
        } catch (UnknownHostException ex) {
            return ip;
        }
    }
}
