package com.huuhv.foodsndrinks.logging;

import com.huuhv.foodsndrinks.config.AsyncConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra {@link MdcTaskDecorator} qua đúng cơ chế Spring Boot: bean {@code TaskDecorator} trong {@link AsyncConfig}
 * được {@link ThreadPoolTaskExecutorBuilder} tự gắn vào mọi executor tạo từ builder.
 */
class MdcTaskDecoratorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mdcOfCaller_isVisibleInNotifyThread() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            MDC.put(RequestIdFilter.MDC_KEY, "req-42");
            MDC.put(MdcUserFilter.MDC_KEY, "alice");

            Map<String, String> seen = executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);
            String threadName = executor.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

            assertThat(threadName).startsWith("notify-");
            assertThat(seen)
                    .containsEntry(RequestIdFilter.MDC_KEY, "req-42")
                    .containsEntry(MdcUserFilter.MDC_KEY, "alice");
        });
    }

    @Test
    void newExecutorBuiltFromBuilder_getsDecoratorAutomatically() {
        contextRunner.withUserConfiguration(ExtraExecutorConfig.class).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("extraExecutor", ThreadPoolTaskExecutor.class);
            MDC.put(RequestIdFilter.MDC_KEY, "req-7");

            String seen = executor.submit(() -> MDC.get(RequestIdFilter.MDC_KEY)).get(5, TimeUnit.SECONDS);

            assertThat(seen).isEqualTo("req-7");
        });
    }

    @Test
    void mdcDoesNotLeak_toLaterTaskOnReusedThread() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);

            MDC.put(RequestIdFilter.MDC_KEY, "req-1");
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            MDC.clear();
            String seen = executor.submit(() -> MDC.get(RequestIdFilter.MDC_KEY)).get(5, TimeUnit.SECONDS);

            assertThat(seen).isNull();
        });
    }

    @Test
    void taskRunOnCallerThread_restoresCallerMdc() {
        MDC.put(RequestIdFilter.MDC_KEY, "caller");
        Runnable decorated = new MdcTaskDecorator().decorate(() -> MDC.put(RequestIdFilter.MDC_KEY, "changed"));

        MDC.put(RequestIdFilter.MDC_KEY, "caller-now");
        decorated.run();

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("caller-now");
    }

    /** Mô phỏng executor mới (vd. excelExecutor ở giai đoạn 7) — không tự gọi setTaskDecorator. */
    @Configuration(proxyBeanMethods = false)
    static class ExtraExecutorConfig {

        @Bean
        ThreadPoolTaskExecutor extraExecutor(ThreadPoolTaskExecutorBuilder builder) {
            return builder.threadNamePrefix("extra-").build();
        }
    }
}
