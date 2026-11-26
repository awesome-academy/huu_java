package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Scheduler/thread pool với context đầy đủ (cấu hình thật của {@code application.yml}):
 * <ul>
 *   <li>ShedLock: job báo cáo tháng chỉ chạy một lần trong {@code lockAtLeastFor} dù bị kích hoạt nhiều lần — tương
 *       đương nhiều instance cùng chạy cron. Khoá nằm trong bảng {@code shedlock} của MySQL.</li>
 *   <li>Executor chờ task khi tắt app; pool {@code @Scheduled} có tên/kích thước rõ ràng.</li>
 * </ul>
 * Cố ý KHÔNG {@code @Transactional}: ShedLock ghi khoá bằng transaction riêng (đã commit, rollback của test không
 * xoá được) — dọn bảng {@code shedlock} trong {@link #removeLock()}. Test không ghi dữ liệu nghiệp vụ nào.
 */
@IntegrationTest
class SchedulingIntegrationTest {

    private static final String LOCK_NAME = "orderMonthlyStatistics";

    @Autowired private OrderStatisticsScheduler scheduler;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("taskExecutor") private ThreadPoolTaskExecutor notifyExecutor;
    @Autowired @Qualifier(AsyncProperties.DASHBOARD_EXECUTOR) private ThreadPoolTaskExecutor dashboardExecutor;
    @Autowired private ThreadPoolTaskScheduler taskScheduler;
    @Value("${app.instance-id}") private String instanceId;

    @MockitoBean private EmailNotificationService emailNotificationService;

    @AfterEach
    void removeLock() {
        jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", LOCK_NAME);
    }

    @Test
    void monthlyStatistics_runsOnlyOnce_whileLockIsHeld() {
        scheduler.sendMonthlyStatistics();
        scheduler.sendMonthlyStatistics();

        verify(emailNotificationService, times(1))
                .sendMonthlyStatistics(anyInt(), anyInt(), any(), anyLong(), any());
        String lockedBy = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM shedlock WHERE name = ?", String.class, LOCK_NAME);
        assertThat(lockedBy).isEqualTo(instanceId);
    }

    @Test
    void executors_waitForTasksOnShutdown() {
        for (ThreadPoolTaskExecutor executor : new ThreadPoolTaskExecutor[]{notifyExecutor, dashboardExecutor}) {
            assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(executor, "awaitTerminationMillis")).isEqualTo(30_000L);
        }
    }

    @Test
    void scheduler_hasDedicatedNamedPool() {
        // getPoolSize() là số thread hiện có (tạo dần khi cần), không phải cấu hình
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("sched-");
        assertThat(ReflectionTestUtils.getField(taskScheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
    }
}
