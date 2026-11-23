package com.huuhv.foodsndrinks.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Câu SQL của {@link JdbcAttemptLimitRepository} trên MySQL thật.
 * <p>
 * Không dùng {@code @Transactional} để rollback: repository cố ý chạy ngoài transaction của người gọi (mỗi câu tự
 * commit), nên mỗi test dùng một {@code limiter} riêng và tự xoá bản ghi của mình sau khi chạy. Thời gian là epoch
 * millis rất nhỏ ({@link #NOW}) để {@code deleteExpired} trong test không xoá bản ghi thật (~1.7e12) của app dev.
 */
@SpringBootTest
class JdbcAttemptLimitRepositoryTest {

    private static final long NOW = 1_000_000L;
    private static final long WINDOW = 60_000L;
    private static final int MAX = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private AttemptLimitRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String limiter = "TEST_" + HexFormat.of().formatHex(randomBytes(8));
    private final byte[] key = randomBytes(32);

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    @AfterEach
    void deleteRowsOfThisTest() {
        jdbcTemplate.update("DELETE FROM attempt_limits WHERE limiter = ?", limiter);
    }

    private boolean acquire(long now) {
        return repository.tryAcquire(limiter, key, MAX, now, now + WINDOW);
    }

    private void acquireMax() {
        for (int i = 0; i < MAX; i++) {
            assertThat(acquire(NOW)).isTrue();
        }
    }

    @Test
    void tryAcquire_countsUpToMax_thenRejects() {
        acquireMax();

        assertThat(acquire(NOW + 1)).isFalse();
    }

    @Test
    void tryAcquire_afterWindowEnds_startsANewFixedWindow() {
        acquireMax();
        assertThat(acquire(NOW + WINDOW - 1)).isFalse();

        assertThat(acquire(NOW + WINDOW)).isTrue();
        // Cửa sổ mới bắt đầu từ NOW + WINDOW: còn MAX - 1 lần
        assertThat(acquire(NOW + WINDOW)).isTrue();
        assertThat(acquire(NOW + WINDOW)).isTrue();
        assertThat(acquire(NOW + WINDOW)).isFalse();
    }

    @Test
    void lockIfReached_locksOnlyOnceMaxIsReached_andClearsTheCounter() {
        acquire(NOW);
        acquire(NOW);
        assertThat(repository.lockIfReached(limiter, key, MAX, NOW, NOW + 600_000)).isFalse();
        assertThat(repository.findLockedUntil(limiter, key)).isZero();

        acquire(NOW);
        assertThat(repository.lockIfReached(limiter, key, MAX, NOW, NOW + 600_000)).isTrue();
        assertThat(repository.lockIfReached(limiter, key, MAX, NOW, NOW + 600_000))
                .as("request song song không khoá/audit lần nữa").isFalse();
        assertThat(repository.findLockedUntil(limiter, key)).isEqualTo(NOW + 600_000);
        assertThat(acquire(NOW)).as("bộ đếm đã được xoá khi khoá").isTrue();
    }

    @Test
    void lockIfReached_afterWindowEnds_doesNotLock() {
        acquireMax();

        assertThat(repository.lockIfReached(limiter, key, MAX, NOW + WINDOW, NOW + WINDOW + 600_000)).isFalse();
    }

    @Test
    void release_returnsOneSlot_andReset_clearsTheCounter() {
        acquireMax();
        repository.release(limiter, key, NOW);
        assertThat(acquire(NOW)).isTrue();
        assertThat(acquire(NOW)).isFalse();

        repository.resetAttempts(limiter, key);
        acquireMax();
    }

    @Test
    void findLockedUntil_withoutRow_returnsZero() {
        assertThat(repository.findLockedUntil(limiter, key)).isZero();
    }

    @Test
    void concurrentAcquires_onSeparateConnections_neverExceedMax() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> acquire(NOW));
            }
            long acquired = 0;
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                if (result.get()) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(MAX);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void attemptsAreKept_whenTheCallersTransactionRollsBack() {
        // vd. đổi mật khẩu sai: UserService ném exception → transaction rollback, nhưng lần sai vẫn phải được đếm
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            acquireMax();
            status.setRollbackOnly();
        });

        assertThat(acquire(NOW)).isFalse();
    }

    @Test
    void deleteExpired_removesOnlyRowsWhoseWindowAndLockHaveBothEnded() {
        byte[] lockedKey = randomBytes(32);
        acquire(NOW); // hết cửa sổ lúc NOW + WINDOW
        repository.tryAcquire(limiter, lockedKey, 1, NOW, NOW + WINDOW);
        repository.lockIfReached(limiter, lockedKey, 1, NOW, NOW + 2 * WINDOW);

        repository.deleteExpired(NOW + WINDOW, 100);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempt_limits WHERE limiter = ?", Integer.class,
                limiter)).isEqualTo(1);
        assertThat(repository.findLockedUntil(limiter, lockedKey)).isEqualTo(NOW + 2 * WINDOW);

        repository.deleteExpired(NOW + 2 * WINDOW, 100);

        assertThat(repository.findLockedUntil(limiter, lockedKey)).isZero();
    }
}
