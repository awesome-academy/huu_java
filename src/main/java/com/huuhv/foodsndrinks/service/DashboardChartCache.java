package com.huuhv.foodsndrinks.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huuhv.foodsndrinks.config.properties.DashboardProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.service.websocket.OrdersChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache dữ liệu biểu đồ dashboard ({@link DashboardService#getChartData()}) trong bộ nhớ instance.
 * <p>
 * Mỗi sự kiện đơn hàng làm MỌI dashboard đang mở (mọi admin, mọi instance) tải lại biểu đồ — 3 query gom nhóm mỗi lần.
 * Cache gộp các lần tải đó thành một lần tính mỗi instance: nhiều request cùng lúc chờ một lần tính (Caffeine tính
 * nguyên tử theo key), không chạy query song song.
 * <p>
 * Luôn đúng sau sự kiện: {@code AdminOrderNotifier} phát {@link OrdersChangedEvent} (đồng bộ) TRƯỚC khi đẩy STOMP →
 * cache bị xoá trước khi client nhận sự kiện và tải lại. Mỗi giá trị mang "thế hệ" lúc bắt đầu tính; sự kiện tăng thế
 * hệ, nên giá trị tính dở lúc sự kiện tới (có thể đọc trước commit) không bao giờ được trả cho request đến sau sự kiện.
 * Thay đổi không có sự kiện (đổi tên sản phẩm) hiện sau tối đa {@code app.dashboard.chart-cache-ttl}. Nhiều instance
 * không sticky: request tải lại có thể tới instance chưa kịp nhận message JMS (chênh vài ms, client chờ ≥ 1s mới tải) —
 * trường hợp xấu nhất cũng chỉ trễ một TTL.
 * <p>
 * Constructor viết tay vì cache được dựng từ cấu hình (TTL {@code 0s} = tắt, profile test).
 */
@Service
public class DashboardChartCache {

    private static final String KEY = "charts";

    private final DashboardService dashboardService;
    private final Cache<String, Entry> cache;
    private final AtomicLong generation = new AtomicLong();

    public DashboardChartCache(DashboardService dashboardService, DashboardProperties properties) {
        this.dashboardService = dashboardService;
        Duration ttl = properties.chartCacheTtl();
        this.cache = ttl.isZero() ? null : Caffeine.newBuilder().maximumSize(1).expireAfterWrite(ttl).build();
    }

    /** Lỗi khi tính (DB) được ném lại, không lưu gì vào cache. */
    public DashboardChartResDto get() {
        if (cache == null) {
            return dashboardService.getChartData();
        }
        long current = generation.get();
        Entry entry = cache.get(KEY, key -> compute(current));
        if (entry.generation() < current) {
            // Tính trước sự kiện gần nhất → bỏ, tính lại (đúng một lần: sự kiện mới hơn nữa thì lần tải sau xử lý)
            cache.asMap().remove(KEY, entry);
            entry = cache.get(KEY, key -> compute(current));
        }
        return entry.charts();
    }

    @EventListener
    public void onOrdersChanged(OrdersChangedEvent event) {
        if (cache != null) {
            generation.incrementAndGet();
            cache.invalidateAll();
        }
    }

    private Entry compute(long startedAtGeneration) {
        return new Entry(startedAtGeneration, dashboardService.getChartData());
    }

    private record Entry(long generation, DashboardChartResDto charts) {
    }
}
