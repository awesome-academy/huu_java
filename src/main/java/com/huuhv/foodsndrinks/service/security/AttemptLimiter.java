package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.repository.AttemptLimitRepository;

import java.time.Duration;
import java.time.InstantSource;
import java.util.function.Function;

/**
 * Bộ đếm số lần thử theo key trong một cửa sổ thời gian cố định (tính từ lần thử ĐẦU TIÊN, không trượt), kèm khoá tạm
 * thời khi đủ số lần sai. Dùng cho chống brute-force đăng nhập/đổi mật khẩu và giới hạn tần suất đăng ký. Tạo qua
 * {@link AttemptLimiterFactory}.
 * <p>
 * <b>Giữ chỗ trước, kết luận sau:</b> {@link #tryAcquire} tăng bộ đếm NGAY khi bắt đầu một lần thử (trước bước tốn thời
 * gian như so BCrypt) và từ chối khi đã đủ {@code max} lần đang chạy hoặc đã sai. Nếu chỉ đếm sau khi so mật khẩu, nhiều
 * request song song cùng lọt qua bước kiểm tra lúc bộ đếm còn thấp. Sau đó người gọi kết luận: {@link #recordFailure}
 * (giữ lần đếm, đủ ngưỡng thì khoá), {@link #reset} (thành công, xoá bộ đếm) hoặc {@link #release} (trả lại lần đếm).
 * <p>
 * Bộ đếm và khoá nằm trong bảng {@code attempt_limits} ({@link AttemptLimitRepository}): mọi instance sau load balancer
 * dùng chung, restart/deploy không xoá khoá. Key được băm HMAC trước khi lưu, không lưu IP/tên đăng nhập dạng rõ. Thời
 * gian lấy theo đồng hồ của JVM — các instance phải đồng bộ giờ (NTP); lệch vài giây chỉ làm cửa sổ/khoá dài ngắn tương
 * ứng.
 */
final class AttemptLimiter {

    /** Độ dài tối đa của cột {@code attempt_limits.limiter}. */
    static final int MAX_NAME_LENGTH = 32;

    private final String name;
    private final int max;
    private final Duration window;
    private final Duration lockDuration;
    private final AttemptLimitRepository repository;
    private final Function<String, byte[]> keyHasher;
    private final InstantSource clock;

    /**
     * @param name tên phạm vi: cột {@code limiter} (tách bộ đếm giữa các phạm vi) và trong log
     * @param max  số lần sai tối đa trong {@code window}; cũng là số lần thử được chạy đồng thời
     */
    AttemptLimiter(String name, int max, Duration window, Duration lockDuration, AttemptLimitRepository repository,
                   Function<String, byte[]> keyHasher, InstantSource clock) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Limiter name must be 1-" + MAX_NAME_LENGTH + " characters: " + name);
        }
        this.name = name;
        this.max = max;
        this.window = window;
        this.lockDuration = lockDuration;
        this.repository = repository;
        this.keyHasher = keyHasher;
        this.clock = clock;
    }

    int max() {
        return max;
    }

    Duration lockDuration() {
        return lockDuration;
    }

    /** @return số giây còn lại của khoá (làm tròn lên, tối thiểu 1); 0 nếu key không bị khoá */
    long lockedForSeconds(String key) {
        long remainingMillis = repository.findLockedUntil(name, keyHasher.apply(key)) - clock.millis();
        if (remainingMillis <= 0) {
            return 0;
        }
        return Math.max(1, (remainingMillis + 999) / 1000);
    }

    /**
     * Giữ chỗ một lần thử (không kiểm tra khoá — gọi {@link #lockedForSeconds} trước).
     *
     * @return {@code false} nếu đã có {@code max} lần thử đang chạy hoặc đã sai trong cửa sổ (không tăng bộ đếm)
     */
    boolean tryAcquire(String key) {
        long now = clock.millis();
        return repository.tryAcquire(name, keyHasher.apply(key), max, now, now + window.toMillis());
    }

    /**
     * Lần thử đã giữ chỗ thất bại: giữ nguyên lần đếm; đủ {@code max} thì khoá key và xoá bộ đếm.
     *
     * @return số lần sai nếu lần này làm key bị khoá, ngược lại 0
     */
    int recordFailure(String key) {
        long now = clock.millis();
        return repository.lockIfReached(name, keyHasher.apply(key), max, now, now + lockDuration.toMillis()) ? max : 0;
    }

    /** Lần thử thành công: xoá bộ đếm (gồm cả lần đã giữ chỗ). */
    void reset(String key) {
        repository.resetAttempts(name, keyHasher.apply(key));
    }

    /** Trả lại một lần đã giữ chỗ mà không tính là sai (vd. thành công nhưng không được phép xoá cả bộ đếm). */
    void release(String key) {
        repository.release(name, keyHasher.apply(key), clock.millis());
    }
}
