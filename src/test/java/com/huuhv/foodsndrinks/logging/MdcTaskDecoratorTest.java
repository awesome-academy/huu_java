package com.huuhv.foodsndrinks.logging;

import com.huuhv.foodsndrinks.config.AsyncConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Dùng đúng executor {@code taskExecutor} của {@link AsyncConfig} (pool chạy các job {@code @Async}). */
class MdcTaskDecoratorTest {

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void createExecutor() {
        executor = (ThreadPoolTaskExecutor) new AsyncConfig().getAsyncExecutor();
    }

    @AfterEach
    void shutdown() {
        executor.shutdown();
        MDC.clear();
    }

    @Test
    void mdcOfCaller_isVisibleInAsyncThread() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        MDC.put(MdcUserFilter.MDC_KEY, "alice");

        Map<String, String> seen = executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);
        String threadName = executor.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

        assertThat(threadName).startsWith("notify-");
        assertThat(seen)
                .containsEntry(RequestIdFilter.MDC_KEY, "req-42")
                .containsEntry(MdcUserFilter.MDC_KEY, "alice");
    }

    @Test
    void mdcDoesNotLeak_toLaterTaskOnReusedThread() throws Exception {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);

        MDC.put(RequestIdFilter.MDC_KEY, "req-1");
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

        MDC.clear();
        String seen = executor.submit(() -> MDC.get(RequestIdFilter.MDC_KEY)).get(5, TimeUnit.SECONDS);

        assertThat(seen).isNull();
    }

    @Test
    void taskRunOnCallerThread_restoresCallerMdc() {
        MDC.put(RequestIdFilter.MDC_KEY, "caller");
        Runnable decorated = new MdcTaskDecorator().decorate(() -> MDC.put(RequestIdFilter.MDC_KEY, "changed"));

        MDC.put(RequestIdFilter.MDC_KEY, "caller-now");
        decorated.run();

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("caller-now");
    }
}
