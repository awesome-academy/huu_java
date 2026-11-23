package com.huuhv.foodsndrinks.repository;

/**
 * Bảng {@code attempt_limits}: bộ đếm số lần thử + khoá tạm thời, dùng chung giữa mọi instance. Logic (ngưỡng, cửa sổ,
 * băm key) nằm ở {@code service/security/AttemptLimiter}; repository chỉ thực hiện từng bước một cách nguyên tử.
 * <p>
 * Thời gian là epoch millis do người gọi truyền vào. Mọi method chạy <b>ngoài</b> transaction của người gọi (mỗi câu
 * lệnh tự commit): lần sai phải được ghi kể cả khi transaction của người gọi rollback (vd. đổi mật khẩu sai ném
 * exception), và phải đọc được thay đổi mới nhất của instance khác thay vì snapshot của transaction đang mở.
 *
 * @see JdbcAttemptLimitRepository
 */
public interface AttemptLimitRepository {

    /** @return thời điểm hết khoá của key, 0 nếu chưa có bản ghi (có thể đã qua — người gọi so với thời điểm hiện tại) */
    long findLockedUntil(String limiter, byte[] keyHash);

    /**
     * Giữ chỗ một lần thử: cửa sổ đã hết (hoặc chưa có bản ghi) thì bắt đầu cửa sổ mới với 1 lần thử, còn trong cửa sổ
     * thì tăng bộ đếm nếu chưa đủ {@code max}. Nguyên tử với request song song ở mọi instance.
     *
     * @param windowEndsAt hết cửa sổ nếu lần này bắt đầu cửa sổ mới
     * @return {@code false} nếu đã có {@code max} lần thử trong cửa sổ (bộ đếm không đổi)
     */
    boolean tryAcquire(String limiter, byte[] keyHash, int max, long now, long windowEndsAt);

    /**
     * Khoá key tới {@code lockedUntil} và xoá bộ đếm nếu trong cửa sổ đã có ít nhất {@code max} lần.
     *
     * @return {@code true} nếu lần gọi này khoá key (request song song chỉ một request nhận {@code true})
     */
    boolean lockIfReached(String limiter, byte[] keyHash, int max, long now, long lockedUntil);

    /** Xoá bộ đếm (không đụng tới khoá). */
    void resetAttempts(String limiter, byte[] keyHash);

    /** Trả lại một lần đã giữ chỗ (nếu cửa sổ chưa hết). */
    void release(String limiter, byte[] keyHash, long now);

    /** Xoá tối đa {@code limit} bản ghi đã hết cả cửa sổ lẫn khoá; trả về số bản ghi đã xoá. */
    int deleteExpired(long now, int limit);
}
