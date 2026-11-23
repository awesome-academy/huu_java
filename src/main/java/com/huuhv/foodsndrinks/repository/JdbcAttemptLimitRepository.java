package com.huuhv.foodsndrinks.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AttemptLimitRepository} trên MySQL bằng câu lệnh nguyên tử (không SELECT rồi UPDATE). {@code NOT_SUPPORTED}
 * tạm dừng transaction của người gọi nên mỗi câu lệnh chạy trên connection riêng ở chế độ auto-commit.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class JdbcAttemptLimitRepository implements AttemptLimitRepository {

    private static final String FIND_LOCKED_UNTIL =
            "SELECT locked_until FROM attempt_limits WHERE limiter = ? AND key_hash = ?";

    // MySQL gán SET từ trái sang phải và vế sau thấy giá trị mới của vế trước: attempts phải đứng trước window_ends_at
    // để cả hai IF cùng so với window_ends_at cũ
    private static final String INCREMENT = """
            UPDATE attempt_limits
               SET attempts = IF(window_ends_at <= ?, 1, attempts + 1),
                   window_ends_at = IF(window_ends_at <= ?, ?, window_ends_at)
             WHERE limiter = ? AND key_hash = ? AND (window_ends_at <= ? OR attempts < ?)""";

    private static final String INSERT_FIRST_ATTEMPT = """
            INSERT IGNORE INTO attempt_limits (limiter, key_hash, attempts, window_ends_at)
            VALUES (?, ?, 1, ?)""";

    private static final String LOCK_IF_REACHED = """
            UPDATE attempt_limits
               SET locked_until = ?, attempts = 0, window_ends_at = 0
             WHERE limiter = ? AND key_hash = ? AND window_ends_at > ? AND attempts >= ?""";

    private static final String RESET =
            "UPDATE attempt_limits SET attempts = 0, window_ends_at = 0 WHERE limiter = ? AND key_hash = ?";

    private static final String RELEASE = """
            UPDATE attempt_limits SET attempts = attempts - 1
             WHERE limiter = ? AND key_hash = ? AND attempts > 0 AND window_ends_at > ?""";

    private static final String DELETE_EXPIRED =
            "DELETE FROM attempt_limits WHERE expires_at <= ? ORDER BY expires_at LIMIT ?";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public long findLockedUntil(String limiter, byte[] keyHash) {
        return jdbcTemplate.queryForList(FIND_LOCKED_UNTIL, Long.class, limiter, keyHash).stream()
                .findFirst().orElse(0L);
    }

    @Override
    public boolean tryAcquire(String limiter, byte[] keyHash, int max, long now, long windowEndsAt) {
        try {
            if (increment(limiter, keyHash, max, now, windowEndsAt)) {
                return true;
            }
            // Chưa có bản ghi → tạo với 1 lần thử. Không chèn được nghĩa là bản ghi đã có (đã đủ max, hoặc request song
            // song vừa tạo) → thử tăng lại một lần, kết quả lần này là quyết định
            if (jdbcTemplate.update(INSERT_FIRST_ATTEMPT, limiter, keyHash, windowEndsAt) == 1) {
                return true;
            }
            return increment(limiter, keyHash, max, now, windowEndsAt);
        } catch (PessimisticLockingFailureException e) {
            // Deadlock/lock timeout khi rất nhiều request cùng key: từ chối (người dùng thử lại sau 1 giây)
            log.warn("Attempt limiter '{}' could not acquire a row lock, rejecting the attempt", limiter);
            return false;
        }
    }

    private boolean increment(String limiter, byte[] keyHash, int max, long now, long windowEndsAt) {
        return jdbcTemplate.update(INCREMENT, now, now, windowEndsAt, limiter, keyHash, now, max) == 1;
    }

    @Override
    public boolean lockIfReached(String limiter, byte[] keyHash, int max, long now, long lockedUntil) {
        return jdbcTemplate.update(LOCK_IF_REACHED, lockedUntil, limiter, keyHash, now, max) == 1;
    }

    @Override
    public void resetAttempts(String limiter, byte[] keyHash) {
        jdbcTemplate.update(RESET, limiter, keyHash);
    }

    @Override
    public void release(String limiter, byte[] keyHash, long now) {
        jdbcTemplate.update(RELEASE, limiter, keyHash, now);
    }

    @Override
    public int deleteExpired(long now, int limit) {
        return jdbcTemplate.update(DELETE_EXPIRED, now, limit);
    }
}
