package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import com.huuhv.foodsndrinks.config.properties.DashboardProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.service.websocket.OrdersChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dữ liệu biểu đồ được tính một lần cho mọi admin đang mở dashboard; sự kiện đơn hàng xoá cache để lần tải lại đọc số
 * liệu mới — kể cả khi sự kiện tới lúc một lần tính (đọc trước commit) đang chạy dở. Lần tính chạy trên executor
 * dashboard, request chờ có giới hạn thời gian.
 */
class DashboardChartCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final DashboardService dashboardService = mock(DashboardService.class);
    private final ExecutorService dashboardExecutor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        dashboardExecutor.shutdownNow();
    }

    @Test
    void concurrentAndRepeatedRequests_computeOnce() throws Exception {
        CountDownLatch computing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dashboardService.getChartData()).thenAnswer(invocation -> {
            computing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return charts(1);
        });
        DashboardChartCache cache = cache(TTL);
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            Future<DashboardChartResDto> first = threads.submit(cache::get);
            assertThat(computing.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<DashboardChartResDto>> waiting = List.of(threads.submit(cache::get),
                    threads.submit(cache::get), threads.submit(cache::get));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(charts(1));
            for (Future<DashboardChartResDto> future : waiting) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(charts(1));
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(cache.get()).isEqualTo(charts(1));
        verify(dashboardService, times(1)).getChartData();
    }

    @Test
    void orderEvent_evictsCache_soNextRequestReadsFreshData() {
        when(dashboardService.getChartData()).thenReturn(charts(1), charts(2));
        DashboardChartCache cache = cache(TTL);
        assertThat(cache.get()).isEqualTo(charts(1));

        cache.onOrdersChanged(new OrdersChangedEvent(42L));

        assertThat(cache.get()).isEqualTo(charts(2));
    }

    @Test
    void eventDuringInFlightComputation_doesNotLeaveStaleDataCached() throws Exception {
        // Lần tính bắt đầu TRƯỚC commit (số liệu cũ); sự kiện của commit tới khi lần tính đang chạy dở
        CountDownLatch computing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(dashboardService.getChartData()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                computing.countDown();
                release.await(5, TimeUnit.SECONDS);
                return charts(1);
            }
            return charts(2);
        });
        DashboardChartCache cache = cache(TTL);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<DashboardChartResDto> stale = threads.submit(cache::get);
            assertThat(computing.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> evict = threads.submit(() -> cache.onOrdersChanged(new OrdersChangedEvent(42L)));
            release.countDown();
            assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo(charts(1));
            evict.get(5, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        // Lần tải do sự kiện kích hoạt (đến sau sự kiện) phải thấy số liệu mới
        assertThat(cache.get()).isEqualTo(charts(2));
    }

    @Test
    void failure_becomesServiceUnavailable_andIsNotCached() {
        when(dashboardService.getChartData()).thenThrow(new IllegalStateException("db down")).thenReturn(charts(1));
        DashboardChartCache cache = cache(TTL);

        assertThatThrownBy(cache::get).isInstanceOf(DashboardUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(cache.get()).isEqualTo(charts(1));
    }

    @Test
    void slowQueries_failFastWithTimeout_andFinishedResultIsStillShared() throws Exception {
        // DB chậm: request không chờ vô hạn; lần tính không bị huỷ — xong thì request sau dùng lại
        CountDownLatch release = new CountDownLatch(1);
        when(dashboardService.getChartData()).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return charts(1);
        });
        DashboardChartCache cache = cache(TTL, dashboardExecutor, Duration.ofMillis(100));

        assertThatThrownBy(cache::get).isInstanceOf(DashboardUnavailableException.class)
                .hasMessageContaining("timed out");

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(cache.get()).isEqualTo(charts(1)));
        verify(dashboardService, times(1)).getChartData();
    }

    @Test
    void saturatedExecutor_becomesServiceUnavailable_withoutRunningOnCallerThread() {
        when(dashboardService.getChartData()).thenReturn(charts(1));
        AtomicBoolean saturated = new AtomicBoolean(true);
        Executor executor = task -> {
            if (saturated.get()) {
                throw new RejectedExecutionException("pool full");
            }
            dashboardExecutor.execute(task);
        };
        DashboardChartCache cache = cache(TTL, executor, TIMEOUT);

        assertThatThrownBy(cache::get).isInstanceOf(DashboardUnavailableException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);

        saturated.set(false);
        assertThat(cache.get()).isEqualTo(charts(1));
    }

    @Test
    void zeroTtl_disablesCache() {
        when(dashboardService.getChartData()).thenReturn(charts(1), charts(2));
        DashboardChartCache cache = cache(Duration.ZERO);

        assertThat(cache.get()).isEqualTo(charts(1));
        assertThat(cache.get()).isEqualTo(charts(2));
        cache.onOrdersChanged(new OrdersChangedEvent(1L));   // không lỗi khi tắt
    }

    private DashboardChartCache cache(Duration ttl) {
        return cache(ttl, dashboardExecutor, TIMEOUT);
    }

    private DashboardChartCache cache(Duration ttl, Executor executor, Duration timeout) {
        AsyncProperties async = new AsyncProperties(new AsyncProperties.Pool(2, 4, 100),
                new AsyncProperties.DashboardPool(4, 4, 50, timeout), new AsyncProperties.OutboxPool(1, 2, 100));
        return new DashboardChartCache(dashboardService, new DashboardProperties(ttl), executor, async);
    }

    private static DashboardChartResDto charts(long completed) {
        return new DashboardChartResDto(List.of("T11"), List.of(), List.of("Hoàn thành"), List.of(completed),
                List.of(), List.of());
    }
}
