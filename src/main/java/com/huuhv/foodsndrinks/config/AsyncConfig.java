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
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Cấu hình {@code @Async}/{@code @Scheduled}.
 * <p>
 * Mọi executor phải được tạo từ {@link ThreadPoolTaskExecutorBuilder} (bean do Spring Boot cấu hình): builder tự gắn
 * mọi bean {@link TaskDecorator} — ở đây là {@link MdcTaskDecorator} mang requestId/user sang thread async — và chờ
 * task đang chạy/trong hàng đợi khi tắt app ({@code spring.task.execution.shutdown.*}), nên executor mới (vd.
 * {@code excelExecutor}) không thể quên các bước này. Không tự {@code new ThreadPoolTaskExecutor()}. Kích thước pool
 * đọc từ {@link AsyncProperties}. Khi pool và hàng đợi đều đầy: việc không được mất (thông báo) chạy trên thread gọi
 * ({@code CallerRunsPolicy}); việc phục vụ request đang chờ có timeout (dashboard) bị từ chối ({@code AbortPolicy}) để
 * báo lỗi nhanh thay vì chạy trên thread request.
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
     * {@code notify-*} để dashboard chậm không làm trễ thông báo và ngược lại.
     * <p>
     * {@code AbortPolicy}: pool và hàng đợi đầy thì từ chối, {@code /admin} trả 503. Không dùng {@code CallerRunsPolicy}
     * ở đây: truy vấn sẽ chạy trên thread request, ngoài timeout của dashboard, và (Open Session in View) giữ connection
     * tới hết request — đúng tình huống làm cạn pool Hikari mà pool riêng này muốn tránh.
     */
    @Bean(name = DASHBOARD_EXECUTOR)
    public ThreadPoolTaskExecutor dashboardExecutor(ThreadPoolTaskExecutorBuilder builder, AsyncProperties properties) {
        AsyncProperties.DashboardPool pool = properties.dashboard();
        return withRejectionPolicy(builder
                .corePoolSize(pool.coreSize())
                .maxPoolSize(pool.maxSize())
                .queueCapacity(pool.queueCapacity())
                .threadNamePrefix("dashboard-")
                .build(), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadPoolTaskExecutor withCallerRunsPolicy(ThreadPoolTaskExecutor executor) {
        return withRejectionPolicy(executor, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static ThreadPoolTaskExecutor withRejectionPolicy(ThreadPoolTaskExecutor executor,
                                                              RejectedExecutionHandler policy) {
        // Builder chưa initialize executor (Spring gọi afterPropertiesSet sau khi trả bean) nên đặt ở đây vẫn có hiệu lực
        executor.setRejectedExecutionHandler(policy);
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
