package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.repository.AttemptLimitRepository;

import java.time.InstantSource;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * {@link AttemptLimitRepository} trong bộ nhớ cho unit test, cùng ngữ nghĩa với các câu SQL của
 * {@code JdbcAttemptLimitRepository} (bản thật được kiểm tra trên MySQL ở {@code JdbcAttemptLimitRepositoryTest}).
 */
public class InMemoryAttemptLimitRepository implements AttemptLimitRepository {

    private static final String TEST_KEY_SECRET = "unit-test-attempt-limit-key-secret-0123456789";

    private static final class Row {
        int attempts;
        long windowEndsAt;
        long lockedUntil;
    }

    private final Map<String, Row> rows = new HashMap<>();

    /** Factory dùng repository trong bộ nhớ và đồng hồ {@code clock}. */
    public static AttemptLimiterFactory factory(InstantSource clock) {
        return new AttemptLimiterFactory(new InMemoryAttemptLimitRepository(), TEST_KEY_SECRET, clock);
    }

    private static String id(String limiter, byte[] keyHash) {
        return limiter + ':' + HexFormat.of().formatHex(keyHash);
    }

    @Override
    public synchronized long findLockedUntil(String limiter, byte[] keyHash) {
        Row row = rows.get(id(limiter, keyHash));
        return row == null ? 0 : row.lockedUntil;
    }

    @Override
    public synchronized boolean tryAcquire(String limiter, byte[] keyHash, int max, long now, long windowEndsAt) {
        Row row = rows.computeIfAbsent(id(limiter, keyHash), k -> new Row());
        if (row.windowEndsAt <= now) {
            row.attempts = 1;
            row.windowEndsAt = windowEndsAt;
            return true;
        }
        if (row.attempts < max) {
            row.attempts++;
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean lockIfReached(String limiter, byte[] keyHash, int max, long now, long lockedUntil) {
        Row row = rows.get(id(limiter, keyHash));
        if (row == null || row.windowEndsAt <= now || row.attempts < max) {
            return false;
        }
        row.lockedUntil = lockedUntil;
        row.attempts = 0;
        row.windowEndsAt = 0;
        return true;
    }

    @Override
    public synchronized void resetAttempts(String limiter, byte[] keyHash) {
        Row row = rows.get(id(limiter, keyHash));
        if (row != null) {
            row.attempts = 0;
            row.windowEndsAt = 0;
        }
    }

    @Override
    public synchronized void release(String limiter, byte[] keyHash, long now) {
        Row row = rows.get(id(limiter, keyHash));
        if (row != null && row.attempts > 0 && row.windowEndsAt > now) {
            row.attempts--;
        }
    }

    @Override
    public synchronized int deleteExpired(long now, int limit) {
        int[] deleted = {0};
        rows.values().removeIf(row -> deleted[0] < limit
                && Math.max(row.windowEndsAt, row.lockedUntil) <= now && ++deleted[0] > 0);
        return deleted[0];
    }
}
