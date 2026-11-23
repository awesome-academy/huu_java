package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Các pool của {@link AsyncConfig}: kích thước từ {@link AsyncProperties}, chính sách khi đầy (notify chạy trên thread
 * gọi, dashboard từ chối), cấu hình sai thì app không khởi động. (Chờ task khi tắt app do {@code application.yml} bật
 * — kiểm tra trong {@code SchedulingIntegrationTest} với context đầy đủ.)
 */
class AsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class);

    @Test
    void executors_useDefaultSizes_andCallerRunsPolicy() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor notify = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor dashboard = context.getBean(AsyncConfig.DASHBOARD_EXECUTOR, ThreadPoolTaskExecutor.class);

            assertThat(notify.getCorePoolSize()).isEqualTo(2);
            assertThat(notify.getMaxPoolSize()).isEqualTo(4);
            assertThat(notify.getQueueCapacity()).isEqualTo(100);
            assertThat(notify.getThreadNamePrefix()).isEqualTo("notify-");

            assertThat(dashboard.getCorePoolSize()).isEqualTo(4);
            assertThat(dashboard.getMaxPoolSize()).isEqualTo(4);
            assertThat(dashboard.getQueueCapacity()).isEqualTo(50);
            assertThat(dashboard.getThreadNamePrefix()).isEqualTo("dashboard-");
            assertThat(context.getBean(AsyncProperties.class).dashboard().timeout()).isEqualTo(Duration.ofSeconds(10));

            assertThat(notify.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThat(dashboard.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Test
    void saturatedDashboardPool_rejects_insteadOfRunningOnCallerThread() {
        contextRunner
                .withPropertyValues("app.async.dashboard.core-size=1", "app.async.dashboard.max-size=1",
                        "app.async.dashboard.queue-capacity=0")
                .run(context -> {
                    ThreadPoolTaskExecutor dashboard =
                            context.getBean(AsyncConfig.DASHBOARD_EXECUTOR, ThreadPoolTaskExecutor.class);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch started = new CountDownLatch(1);
                    Future<?> blocker = dashboard.submit(() -> {
                        started.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return null;
                    });
                    try {
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                        assertThatThrownBy(() -> dashboard.execute(() -> { }))
                                .isInstanceOf(RejectedExecutionException.class);
                    } finally {
                        release.countDown();
                        blocker.get(5, TimeUnit.SECONDS);
                    }
                });
    }

    @Test
    void poolSizes_comeFromProperties() {
        contextRunner
                .withPropertyValues("app.async.notification.core-size=1", "app.async.notification.max-size=3",
                        "app.async.dashboard.core-size=2", "app.async.dashboard.max-size=6",
                        "app.async.dashboard.queue-capacity=10", "app.async.dashboard.timeout=3s",
                        AsyncConfig.HIKARI_MAX_POOL_SIZE + "=12")
                .run(context -> {
                    ThreadPoolTaskExecutor notify = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor dashboard =
                            context.getBean(AsyncConfig.DASHBOARD_EXECUTOR, ThreadPoolTaskExecutor.class);

                    assertThat(notify.getCorePoolSize()).isEqualTo(1);
                    assertThat(notify.getMaxPoolSize()).isEqualTo(3);
                    assertThat(dashboard.getCorePoolSize()).isEqualTo(2);
                    assertThat(dashboard.getMaxPoolSize()).isEqualTo(6);
                    assertThat(dashboard.getQueueCapacity()).isEqualTo(10);
                    assertThat(context.getBean(AsyncProperties.class).dashboard().timeout())
                            .isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void maxSizeBelowCoreSize_failsStartup() {
        contextRunner
                .withPropertyValues("app.async.dashboard.core-size=4", "app.async.dashboard.max-size=2")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("max-size must be >= core-size"));
    }

    @Test
    void maxSizeBelowCoreSize_failsStartup_forEveryPool() {
        // Ràng buộc khai báo ở interface PoolSize → áp cho cả pool notify
        contextRunner
                .withPropertyValues("app.async.notification.core-size=3", "app.async.notification.max-size=1")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("max-size must be >= core-size"));
    }

    @Test
    void dashboardPoolLargerThanHalfOfHikariPool_failsStartup() {
        contextRunner
                .withPropertyValues("app.async.dashboard.max-size=6")   // Hikari mặc định 10 → tối đa 5
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("must be <= half of"));
        contextRunner
                .withPropertyValues("app.async.dashboard.max-size=5")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nonPositiveTimeout_failsStartup() {
        contextRunner
                .withPropertyValues("app.async.dashboard.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void saturatedPool_runsTaskOnCallerThread_insteadOfRejecting() {
        contextRunner
                .withPropertyValues("app.async.notification.core-size=1", "app.async.notification.max-size=1",
                        "app.async.notification.queue-capacity=0")
                .run(context -> {
                    ThreadPoolTaskExecutor notify = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch started = new CountDownLatch(1);
                    Future<?> blocker = notify.submit(() -> {
                        started.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return null;
                    });
                    try {
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                        String ranOn = notify.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

                        assertThat(ranOn).isEqualTo(Thread.currentThread().getName());
                    } finally {
                        release.countDown();
                        blocker.get(5, TimeUnit.SECONDS);
                    }
                });
    }

    @Test
    void saturatedOutboxPool_dropsTask_neverRunsItOnCallerThread() {
        contextRunner
                .withPropertyValues("app.async.outbox.core-size=1", "app.async.outbox.max-size=1",
                        "app.async.outbox.queue-capacity=0")
                .run(context -> {
                    ThreadPoolTaskExecutor outbox = context.getBean(AsyncConfig.OUTBOX_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(outbox.getThreadNamePrefix()).isEqualTo("outbox-");
                    assertThat(outbox.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch started = new CountDownLatch(1);
                    Future<?> blocker = outbox.submit(() -> {
                        started.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return null;
                    });
                    try {
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        Thread caller = Thread.currentThread();
                        boolean[] ranOnCaller = {false};

                        // Đầy: bỏ task (bản ghi outbox vẫn PENDING, job gửi lại), không ném lỗi về callback AFTER_COMMIT
                        outbox.execute(() -> ranOnCaller[0] = Thread.currentThread() == caller);

                        assertThat(ranOnCaller[0]).isFalse();
                    } finally {
                        release.countDown();
                        blocker.get(5, TimeUnit.SECONDS);
                    }
                });
    }
}
