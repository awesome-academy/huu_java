package com.huuhv.foodsndrinks.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import com.huuhv.foodsndrinks.config.properties.DashboardProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.service.websocket.OrdersChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache dữ liệu biểu đồ dashboard ({@link DashboardService#getChartData()}) trong bộ nhớ instance.
 * <p>
 * Mỗi sự kiện đơn hàng làm MỌI dashboard đang mở (mọi admin, mọi instance) tải lại biểu đồ — 3 query gom nhóm mỗi lần.
 * Cache gộp các lần tải đó thành một lần tính mỗi instance: nhiều request cùng lúc chờ cùng một future, không chạy query
 * song song.
 * <p>
 * Lần tính chạy trên {@code dashboardExecutor} (như {@link DashboardService#getDashboardData()}), không chạy trong lúc
 * Caffeine giữ khoá của key: mỗi request chờ tối đa {@code app.async.dashboard.timeout} rồi báo
 * {@link DashboardUnavailableException} (503) — DB chậm không làm thread Tomcat của mọi admin treo vô hạn. Quá giờ
 * KHÔNG huỷ lần tính (dùng chung với request khác; xong thì vẫn vào cache). Pool đầy ({@code AbortPolicy}) hay truy vấn
 * lỗi cũng thành 503; future lỗi bị Caffeine bỏ, không cache.
 * <p>
 * Luôn đúng sau sự kiện: {@code AdminOrderNotifier} phát {@link OrdersChangedEvent} (đồng bộ) TRƯỚC khi đẩy STOMP →
 * cache bị xoá trước khi client nhận sự kiện và tải lại. Mỗi giá trị mang "thế hệ" lúc bắt đầu tính; sự kiện tăng thế
 * hệ, nên giá trị tính dở lúc sự kiện tới (có thể đọc trước commit) không bao giờ được trả cho request đến sau sự kiện.
 * Thay đổi không có sự kiện (đổi tên sản phẩm) hiện sau tối đa {@code app.dashboard.chart-cache-ttl}. Nhiều instance
 * không sticky: request tải lại có thể tới instance chưa kịp nhận message JMS (chênh vài ms, client chờ ≥ 1s mới tải) —
 * trường hợp xấu nhất cũng chỉ trễ một TTL.
 * <p>
 * TTL {@code 0s} (profile test) tắt cache: tính thẳng trên thread gọi, như trước khi có cache. Constructor viết tay vì
 * cache được dựng từ cấu hình.
 */
@Slf4j
@Service
public class DashboardChartCache {

    private static final String KEY = "charts";

    private final DashboardService dashboardService;
    private final Executor dashboardExecutor;
    private final Duration timeout;
    private final AsyncCache<String, Entry> cache;
    private final AtomicLong generation = new AtomicLong();

    public DashboardChartCache(DashboardService dashboardService, DashboardProperties properties,
                               @Qualifier(AsyncProperties.DASHBOARD_EXECUTOR) Executor dashboardExecutor,
                               AsyncProperties asyncProperties) {
        this.dashboardService = dashboardService;
        this.dashboardExecutor = dashboardExecutor;
        this.timeout = asyncProperties.dashboard().timeout();
        Duration ttl = properties.chartCacheTtl();
        this.cache = ttl.isZero() ? null : Caffeine.newBuilder().maximumSize(1).expireAfterWrite(ttl).buildAsync();
    }

    /** @throws DashboardUnavailableException khi truy vấn lỗi, quá thời gian hoặc pool {@code dashboard-*} đầy */
    public DashboardChartResDto get() {
        if (cache == null) {
            return dashboardService.getChartData();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        long current = generation.get();
        CompletableFuture<Entry> future = cache.get(KEY, (key, ignored) -> computeAsync(current));
        Entry entry = await(future, deadline);
        if (entry.generation() < current) {
            // Tính trước sự kiện gần nhất → bỏ, tính lại (đúng một lần: sự kiện mới hơn nữa thì lần tải sau xử lý)
            cache.asMap().remove(KEY, future);
            entry = await(cache.get(KEY, (key, ignored) -> computeAsync(current)), deadline);
        }
        return entry.charts();
    }

    @EventListener
    public void onOrdersChanged(OrdersChangedEvent event) {
        if (cache != null) {
            generation.incrementAndGet();
            cache.synchronous().invalidateAll();
        }
    }

    /** Lỗi được log một lần cho mỗi lần tính (không phải mỗi request đang chờ). */
    private CompletableFuture<Entry> computeAsync(long startedAtGeneration) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> new Entry(startedAtGeneration, dashboardService.getChartData()), dashboardExecutor)
                    .whenComplete((entry, ex) -> {
                        if (ex != null) {
                            log.error("Dashboard chart queries failed", ex);
                        }
                    });
        } catch (RejectedExecutionException ex) {
            log.warn("Dashboard executor is saturated, rejecting chart request");
            return CompletableFuture.failedFuture(ex);
        }
    }

    private Entry await(CompletableFuture<Entry> future, long deadlineNanos) {
        try {
            return future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            log.warn("Dashboard chart queries did not finish within {} ms", timeout.toMillis());
            throw new DashboardUnavailableException("Dashboard chart queries timed out", ex);
        } catch (ExecutionException ex) {
            throw new DashboardUnavailableException("Dashboard chart queries failed", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DashboardUnavailableException("Interrupted while loading dashboard charts", ex);
        }
    }

    private record Entry(long generation, DashboardChartResDto charts) {
    }
}
