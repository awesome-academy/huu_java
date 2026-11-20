package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Cấu hình {@code @Async}/{@code @Scheduled}.
 * <p>
 * Mọi executor phải được tạo từ {@link ThreadPoolTaskExecutorBuilder} (bean do Spring Boot cấu hình): builder tự gắn
 * mọi bean {@link TaskDecorator} — ở đây là {@link MdcTaskDecorator} mang requestId/user sang thread async — và chờ
 * task đang chạy/trong hàng đợi khi tắt app ({@code spring.task.execution.shutdown.*}), nên executor mới (vd.
 * {@code excelExecutor}) không thể quên các bước này. Không tự {@code new ThreadPoolTaskExecutor()}. Kích thước pool
 * đọc từ {@link AsyncProperties}; hàng đợi đầy thì task chạy trên thread gọi ({@code CallerRunsPolicy}) thay vì bị
 * từ chối.
 * <p>
 * Pool của {@code @Scheduled} do Spring Boot tạo theo {@code spring.task.scheduling.*}. Chạy nhiều instance: job
 * {@code @Scheduled} phải có {@code @SchedulerLock} ({@link SchedulerLockConfig}).
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AsyncProperties.class)
public class AsyncConfig implements AsyncConfigurer {

    public static final String DASHBOARD_EXECUTOR = "dashboardExecutor";

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /** Pool chạy thông báo Slack/email ({@code @Async("taskExecutor")}); cũng là executor mặc định của {@code @Async}. */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(ThreadPoolTaskExecutorBuilder builder, AsyncProperties properties) {
        AsyncProperties.Pool pool = properties.notification();
        return withCallerRunsPolicy(builder
                .corePoolSize(pool.coreSize())
                .maxPoolSize(pool.maxSize())
                .queueCapacity(pool.queueCapacity())
                .threadNamePrefix("notify-")
                .build());
    }

    /**
     * Pool chạy song song các truy vấn thống kê của dashboard admin ({@code DashboardService}) — tách khỏi
     * {@code notify-*} để dashboard chậm không làm trễ thông báo và ngược lại. Pool đầy thì truy vấn chạy trên thread
     * request (chậm hơn nhưng không lỗi).
     */
    @Bean(name = DASHBOARD_EXECUTOR)
    public ThreadPoolTaskExecutor dashboardExecutor(ThreadPoolTaskExecutorBuilder builder, AsyncProperties properties) {
        AsyncProperties.DashboardPool pool = properties.dashboard();
        return withCallerRunsPolicy(builder
                .corePoolSize(pool.coreSize())
                .maxPoolSize(pool.maxSize())
                .queueCapacity(pool.queueCapacity())
                .threadNamePrefix("dashboard-")
                .build());
    }

    private static ThreadPoolTaskExecutor withCallerRunsPolicy(ThreadPoolTaskExecutor executor) {
        // Builder chưa initialize executor (Spring gọi afterPropertiesSet sau khi trả bean) nên đặt ở đây vẫn có hiệu lực
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return this::handleUncaughtException;
    }

    private void handleUncaughtException(Throwable ex, Method method, Object... params) {
        log.error("Unhandled exception in async method {}", method.getName(), ex);
    }
}
