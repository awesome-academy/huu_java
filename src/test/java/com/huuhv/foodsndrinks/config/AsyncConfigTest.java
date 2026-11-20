package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Các pool của {@link AsyncConfig}: kích thước từ {@link AsyncProperties}, {@code CallerRunsPolicy} khi đầy, cấu hình
 * sai thì app không khởi động. (Chờ task khi tắt app do {@code application.yml} bật — kiểm tra trong
 * {@code SchedulingIntegrationTest} với context đầy đủ.)
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

            for (ThreadPoolTaskExecutor executor : new ThreadPoolTaskExecutor[]{notify, dashboard}) {
                assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                        .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            }
        });
    }

    @Test
    void poolSizes_comeFromProperties() {
        contextRunner
                .withPropertyValues("app.async.notification.core-size=1", "app.async.notification.max-size=3",
                        "app.async.dashboard.core-size=2", "app.async.dashboard.max-size=6",
                        "app.async.dashboard.queue-capacity=10", "app.async.dashboard.timeout=3s")
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
}
