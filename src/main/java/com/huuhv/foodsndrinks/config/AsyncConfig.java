package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;

/**
 * Cấu hình {@code @Async}/{@code @Scheduled}.
 * <p>
 * Mọi executor phải được tạo từ {@link ThreadPoolTaskExecutorBuilder} (bean do Spring Boot cấu hình): builder tự gắn
 * mọi bean {@link TaskDecorator} — ở đây là {@link MdcTaskDecorator} mang requestId/user sang thread async — nên
 * executor mới (vd. {@code excelExecutor}) không thể quên bước này. Không tự {@code new ThreadPoolTaskExecutor()}.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /** Pool chạy thông báo Slack/email ({@code @Async("taskExecutor")}); cũng là executor mặc định của {@code @Async}. */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder
                .corePoolSize(2)
                .maxPoolSize(4)
                .queueCapacity(100)
                .threadNamePrefix("notify-")
                .build();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return this::handleUncaughtException;
    }

    private void handleUncaughtException(Throwable ex, Method method, Object... params) {
        log.error("Unhandled exception in async method {}", method.getName(), ex);
    }
}
