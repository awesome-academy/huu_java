package com.huuhv.foodsndrinks.service.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bộ đếm số lần thử theo key trong một cửa sổ thời gian cố định (tính từ lần thử ĐẦU TIÊN, không trượt), kèm khoá tạm
 * thời khi đủ số lần sai. Dùng cho chống brute-force đăng nhập/đổi mật khẩu và giới hạn tần suất đăng ký.
 * <p>
 * <b>Giữ chỗ trước, kết luận sau:</b> {@link #tryAcquire} tăng bộ đếm NGAY khi bắt đầu một lần thử (trước bước tốn thời
 * gian như so BCrypt) và từ chối khi đã đủ {@code max} lần đang chạy hoặc đã sai. Nếu chỉ đếm sau khi so mật khẩu, nhiều
 * request song song cùng lọt qua bước kiểm tra lúc bộ đếm còn thấp. Sau đó người gọi kết luận: {@link #recordFailure}
 * (giữ lần đếm, đủ ngưỡng thì khoá), {@link #reset} (thành công, xoá bộ đếm) hoặc {@link #release} (trả lại lần đếm).
 * <p>
 * Bộ đếm và khoá nằm trong hai cache riêng: rải nhiều key rác chỉ đẩy được bộ đếm ra khỏi cache, khoá có giới hạn
 * riêng lớn hơn nhiều (mỗi khoá cần {@code max} lần sai, nên số khoá tăng chậm hơn số bộ đếm). Khoá bị loại vì cache
 * đầy được ghi WARN — đó là dấu hiệu bị tấn công. Bộ đếm trong bộ nhớ JVM: chỉ đúng khi chạy 1 instance.
 */
@Slf4j
final class AttemptLimiter {

    static final long MAX_TRACKED_KEYS = 100_000;
    static final long MAX_LOCKED_KEYS = 100_000;

    private final String name;
    private final int max;
    private final Duration lockDuration;
    private final Ticker ticker;
    /** Số lần thử đang chạy + số lần sai trong cửa sổ hiện tại. */
    private final Cache<String, Integer> attempts;
    /** Thời điểm hết khoá (nano giây theo {@link #ticker}). */
    private final Cache<String, Long> locks;

    /**
     * @param name tên phạm vi, chỉ dùng trong log
     * @param max  số lần sai tối đa trong {@code window}; cũng là số lần thử được chạy đồng thời
     */
    AttemptLimiter(String name, int max, Duration window, Duration lockDuration, Ticker ticker) {
        this.name = name;
        this.max = max;
        this.lockDuration = lockDuration;
        this.ticker = ticker;
        long windowNanos = window.toNanos();
        this.attempts = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .ticker(ticker)
                .executor(Runnable::run)
                .expireAfter(new Expiry<String, Integer>() {
                    @Override
                    public long expireAfterCreate(String key, Integer value, long currentTime) {
                        return windowNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, Integer value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, Integer value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
        this.locks = Caffeine.newBuilder()
                .maximumSize(MAX_LOCKED_KEYS)
                .ticker(ticker)
                .executor(Runnable::run)
                .expireAfterWrite(lockDuration)
                .removalListener((String key, Long value, RemovalCause cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        log.warn("Login limiter '{}' evicted a lock because the lock cache is full", name);
                    }
                })
                .build();
    }

    int max() {
        return max;
    }

    Duration lockDuration() {
        return lockDuration;
    }

    /** @return số giây còn lại của khoá (làm tròn lên, tối thiểu 1); 0 nếu key không bị khoá */
    long lockedForSeconds(String key) {
        Long lockedUntil = locks.getIfPresent(key);
        if (lockedUntil == null) {
            return 0;
        }
        long remainingNanos = Math.max(0, lockedUntil - ticker.read());
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remainingNanos + 999_999_999));
    }

    /**
     * Giữ chỗ một lần thử (không kiểm tra khoá — gọi {@link #lockedForSeconds} trước).
     *
     * @return {@code false} nếu đã có {@code max} lần thử đang chạy hoặc đã sai trong cửa sổ (không tăng bộ đếm)
     */
    boolean tryAcquire(String key) {
        int count = attempts.asMap().merge(key, 1, Integer::sum);
        if (count > max) {
            release(key);
            return false;
        }
        return true;
    }

    /**
     * Lần thử đã giữ chỗ thất bại: giữ nguyên lần đếm; đủ {@code max} thì khoá key và xoá bộ đếm.
     *
     * @return số lần sai nếu lần này làm key bị khoá, ngược lại 0
     */
    int recordFailure(String key) {
        Integer count = attempts.getIfPresent(key);
        if (count == null || count < max) {
            return 0;
        }
        locks.put(key, ticker.read() + lockDuration.toNanos());
        attempts.invalidate(key);
        return count;
    }

    /** Lần thử thành công: xoá bộ đếm (gồm cả lần đã giữ chỗ). */
    void reset(String key) {
        attempts.invalidate(key);
    }

    /** Trả lại một lần đã giữ chỗ mà không tính là sai (vd. thành công nhưng không được phép xoá cả bộ đếm). */
    void release(String key) {
        attempts.asMap().computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
    }
}
