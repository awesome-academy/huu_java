package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.repository.AttemptLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Xoá định kỳ bản ghi {@code attempt_limits} đã hết cả cửa sổ đếm lẫn khoá. Xoá theo lô để không giữ lock lâu; mọi
 * instance cùng chạy job cũng không sao (chỉ xoá bản ghi đã hết hạn).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttemptLimitCleanupJob {

    static final int BATCH_SIZE = 1_000;
    /** Tối đa số lô mỗi lần chạy; còn sót thì lần chạy sau xoá tiếp. */
    static final int MAX_BATCHES = 100;

    private final AttemptLimitRepository repository;

    @Scheduled(initialDelay = 1, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void deleteExpired() {
        long now = System.currentTimeMillis();
        int total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                int deleted = repository.deleteExpired(now, BATCH_SIZE);
                total += deleted;
                if (deleted < BATCH_SIZE) {
                    break;
                }
            }
        } catch (DataAccessException e) {
            log.warn("Could not delete expired attempt limits (deleted {} before the error)", total, e);
            return;
        }
        if (total > 0) {
            log.debug("Deleted {} expired attempt limits", total);
        }
    }
}
