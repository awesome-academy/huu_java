package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import com.huuhv.foodsndrinks.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
    static final String HIKARI_MAX_POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";
    /** Mặc định của HikariCP khi không cấu hình {@value #HIKARI_MAX_POOL_SIZE}. */
    private static final int HIKARI_DEFAULT_MAX = 10;

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /**
     * Executor mặc định của {@code @Async} ({@code notify-*}), cho việc nền gắn với thông báo. Slack/email của đơn hàng
     * không còn chạy ở đây: chúng là consumer JMS của topic {@code order.placed} (thread của listener container, retry do
     * broker quản lý).
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(ThreadPoolTaskExecutorBuilder builder, AsyncProperties properties) {
        return build(builder, properties.notification(), "notify-", new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Pool chạy song song các truy vấn thống kê của dashboard admin ({@code DashboardService}) — tách khỏi
     * {@code notify-*} để dashboard chậm không làm trễ thông báo và ngược lại.
     * <p>
     * {@code AbortPolicy}: pool và hàng đợi đầy thì từ chối, {@code /admin} trả 503. Không dùng {@code CallerRunsPolicy}
     * ở đây: truy vấn sẽ chạy trên thread request, ngoài timeout của dashboard, và (Open Session in View) giữ connection
     * tới hết request — đúng tình huống làm cạn pool Hikari mà pool riêng này muốn tránh.
     * <p>
     * Mỗi thread giữ một connection JDBC, nên {@code app.async.dashboard.max-size} không được quá nửa
     * {@code spring.datasource.hikari.maximum-pool-size} (mặc định 10): vượt thì app không khởi động.
     */
    @Bean(name = DASHBOARD_EXECUTOR)
    public ThreadPoolTaskExecutor dashboardExecutor(ThreadPoolTaskExecutorBuilder builder, AsyncProperties properties,
                                                    Environment environment) {
        AsyncProperties.DashboardPool pool = properties.dashboard();
        int hikariMax = Binder.get(environment).bind(HIKARI_MAX_POOL_SIZE, Integer.class).orElse(HIKARI_DEFAULT_MAX);
        if (pool.maxSize() > hikariMax / 2) {
            throw new IllegalStateException("app.async.dashboard.max-size (" + pool.maxSize() + ") must be <= half of "
                    + HIKARI_MAX_POOL_SIZE + " (" + hikariMax + "): each dashboard thread holds a JDBC connection");
        }
        return build(builder, pool, "dashboard-", new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadPoolTaskExecutor build(ThreadPoolTaskExecutorBuilder builder, AsyncProperties.PoolSize pool,
                                                String threadNamePrefix, RejectedExecutionHandler rejectionPolicy) {
        ThreadPoolTaskExecutor executor = builder
                .corePoolSize(pool.coreSize())
                .maxPoolSize(pool.maxSize())
                .queueCapacity(pool.queueCapacity())
                .threadNamePrefix(threadNamePrefix)
                .build();
        // Builder chưa initialize executor (Spring gọi afterPropertiesSet sau khi trả bean) nên đặt ở đây vẫn có hiệu lực
        executor.setRejectedExecutionHandler(rejectionPolicy);
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
