package com.huuhv.foodsndrinks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Giới hạn số dòng log/audit về frame STOMP lỗi của MỖI user trong một cửa sổ thời gian cố định.
 * <p>
 * Một message WebSocket (≤ 8 KB) chứa được hàng trăm frame STOMP; {@code StompSubProtocolHandler} xử lý tiếp các frame
 * còn lại sau frame lỗi đầu tiên, nên một client đã đăng nhập (đóng kết nối, handshake lại) có thể sinh hàng trăm dòng
 * {@code WEBSOCKET_ACCESS_DENIED} mỗi giây — làm đầy ổ đĩa và chôn các sự kiện thật trong {@code audit.log}. Vượt
 * ngưỡng → không ghi, chỉ đếm; dòng được ghi kế tiếp mang số lần đã bỏ qua để không mất dấu vết.
 * <p>
 * Trạng thái cục bộ từng instance (log cũng ghi theo instance). Số khoá có giới hạn ({@value #MAX_KEYS}, khoá ít dùng bị
 * đẩy ra trước — mất số đếm bỏ qua của khoá đó, chấp nhận được).
 */
final class StompErrorLogThrottle {

    static final int MAX_KEYS = 10_000;

    private final int maxPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final Cache<String, Window> windows;

    StompErrorLogThrottle(int maxPerWindow, Duration window, Clock clock) {
        if (maxPerWindow < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("maxPerWindow must be >= 1 and window > 0");
        }
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = window.toMillis();
        this.clock = clock;
        this.windows = Caffeine.newBuilder()
                .maximumSize(MAX_KEYS)
                .expireAfterAccess(window.multipliedBy(10))
                .build();
    }

    /**
     * @return rỗng nếu lần này KHÔNG được ghi; ngược lại số lần đã bị bỏ qua kể từ dòng được ghi trước đó của khoá này
     */
    OptionalLong tryAcquire(String key) {
        Window window = windows.get(key, k -> new Window(clock.millis()));
        synchronized (window) {
            long now = clock.millis();
            if (now - window.start >= windowMillis) {
                window.start = now;
                window.logged = 0;
            }
            if (window.logged >= maxPerWindow) {
                window.suppressed++;
                return OptionalLong.empty();
            }
            window.logged++;
            long suppressed = window.suppressed;
            window.suppressed = 0;
            return OptionalLong.of(suppressed);
        }
    }

    private static final class Window {
        private long start;
        private int logged;
        private long suppressed;

        private Window(long start) {
            this.start = start;
        }
    }
}
