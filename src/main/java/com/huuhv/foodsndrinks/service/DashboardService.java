package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.config.AsyncConfig;
import com.huuhv.foodsndrinks.config.AsyncProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.dto.response.DashboardResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.repository.OrderDetailRepository;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DashboardService {

    private final OrderRepository       orderRepository;
    private final OrderDetailRepository orderDetailRepository;
    private final ProductRepository     productRepository;
    private final UserRepository        userRepository;
    private final Executor              dashboardExecutor;
    private final TransactionTemplate   readOnlyTx;
    private final Duration              timeout;

    public DashboardService(OrderRepository orderRepository,
                            OrderDetailRepository orderDetailRepository,
                            ProductRepository productRepository,
                            UserRepository userRepository,
                            @Qualifier(AsyncConfig.DASHBOARD_EXECUTOR) Executor dashboardExecutor,
                            PlatformTransactionManager transactionManager,
                            AsyncProperties asyncProperties) {
        this.orderRepository       = orderRepository;
        this.orderDetailRepository = orderDetailRepository;
        this.productRepository     = productRepository;
        this.userRepository        = userRepository;
        this.dashboardExecutor     = dashboardExecutor;
        this.readOnlyTx            = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.timeout               = asyncProperties.dashboard().timeout();
    }

    /**
     * Các truy vấn độc lập chạy song song trên {@code dashboardExecutor}, gộp lại với timeout
     * ({@code app.async.dashboard.timeout}).
     * <p>
     * Cố ý KHÔNG {@code @Transactional}: thread request giữ một connection trong lúc chờ các worker, mỗi worker lại cần
     * một connection → nhiều admin cùng mở dashboard có thể làm cạn pool Hikari. Mỗi truy vấn tự mở transaction
     * read-only của nó và map sang DTO ngay trong transaction đó (không đưa entity ra khỏi thread worker).
     *
     * @throws DashboardUnavailableException khi một truy vấn lỗi hoặc quá thời gian
     */
    public DashboardResDto getDashboardData() {
        long start = System.nanoTime();

        CompletableFuture<BigDecimal> revenue         = query(orderRepository::sumCompletedRevenue);
        CompletableFuture<Long>       completedOrders = query(() -> orderRepository.countByStatus(OrderStatus.COMPLETED));
        CompletableFuture<Long>       activeOrders    = query(orderRepository::countActiveOrders);
        CompletableFuture<Long>       products        = query(productRepository::countByIsAvailableTrue);
        CompletableFuture<Long>       users           = query(userRepository::countByIsActiveTrue);
        CompletableFuture<List<DashboardResDto.MonthlyRevenueDto>> monthly = query(this::buildMonthlyRevenue);
        CompletableFuture<Map<String, Long>>                       status  = query(this::buildStatusCounts);
        CompletableFuture<List<DashboardResDto.RecentOrderDto>>    recent  = query(this::buildRecentOrders);
        CompletableFuture<List<DashboardResDto.TopProductDto>>     top     = query(this::buildTopProducts);

        awaitAll(revenue, completedOrders, activeOrders, products, users, monthly, status, recent, top);

        DashboardResDto result = DashboardResDto.builder()
                .totalRevenue(revenue.join())
                .totalCompletedOrders(completedOrders.join())
                .activeOrders(activeOrders.join())
                .totalProducts(products.join())
                .totalUsers(users.join())
                .monthlyRevenue(monthly.join())
                .statusCounts(status.join())
                .recentOrders(recent.join())
                .topProducts(top.join())
                .build();
        log.debug("Dashboard aggregated in {} ms (parallel)",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return result;
    }

    /** Chỉ dữ liệu biểu đồ (2 query) — cho dashboard.js và cập nhật realtime, không tính lại KPI/bảng. */
    @Transactional(readOnly = true)
    public DashboardChartResDto getChartData() {
        List<DashboardResDto.MonthlyRevenueDto> monthly = buildMonthlyRevenue();
        Map<String, Long> statusCounts = buildStatusCounts();
        return new DashboardChartResDto(
                monthly.stream().map(DashboardResDto.MonthlyRevenueDto::getLabel).toList(),
                monthly.stream().map(DashboardResDto.MonthlyRevenueDto::getRevenue).toList(),
                List.copyOf(statusCounts.keySet()),
                List.copyOf(statusCounts.values()));
    }

    // -------------------------------------------------------
    // Parallel helpers
    // -------------------------------------------------------

    /** Chạy {@code supplier} trên {@code dashboardExecutor}, trong transaction read-only riêng của thread worker. */
    private <T> CompletableFuture<T> query(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> readOnlyTx.execute(status -> supplier.get()), dashboardExecutor);
    }

    /**
     * Chờ mọi truy vấn, tối đa {@link #timeout}. Truy vấn nào lỗi thì báo lỗi ngay (không chờ các truy vấn còn lại);
     * quá thời gian thì huỷ các future chưa xong (truy vấn JDBC đang chạy vẫn tự kết thúc, kết quả bị bỏ).
     */
    private void awaitAll(CompletableFuture<?>... futures) {
        CompletableFuture<Void> all = CompletableFuture.allOf(futures);
        // allOf chỉ hoàn tất khi MỌI future xong — truy vấn đầu tiên lỗi thì kết thúc luôn, không đợi tới timeout
        for (CompletableFuture<?> future : futures) {
            future.whenComplete((value, ex) -> {
                if (ex != null) {
                    all.completeExceptionally(ex);
                }
            });
        }
        try {
            all.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            cancelAll(futures);
            log.error("Dashboard queries did not finish within {} ms", timeout.toMillis());
            throw new DashboardUnavailableException("Dashboard queries timed out", ex);
        } catch (ExecutionException ex) {
            cancelAll(futures);
            log.error("Dashboard query failed", ex.getCause());
            throw new DashboardUnavailableException("Dashboard query failed", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
            throw new DashboardUnavailableException("Interrupted while loading dashboard", ex);
        }
    }

    private static void cancelAll(CompletableFuture<?>... futures) {
        for (CompletableFuture<?> future : futures) {
            future.cancel(false);
        }
    }

    // -------------------------------------------------------
    // Builders
    // -------------------------------------------------------

    /** Last 6 months revenue — fills missing months with 0 */
    private List<DashboardResDto.MonthlyRevenueDto> buildMonthlyRevenue() {
        LocalDateTime from = LocalDateTime.now().minusMonths(5).withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);

        Map<String, Object[]> dataMap = orderRepository.findMonthlyRevenue(from)
                .stream()
                .collect(Collectors.toMap(
                        row -> row[0] + "-" + row[1],  // "2026-1"
                        row -> row
                ));

        List<DashboardResDto.MonthlyRevenueDto> result = new ArrayList<>();
        LocalDateTime cursor = from;
        for (int i = 0; i < 6; i++) {
            int year  = cursor.getYear();
            int month = cursor.getMonthValue();
            String key = year + "-" + month;
            String label = "Th" + month + "/" + year;

            Object[] row = dataMap.get(key);
            result.add(DashboardResDto.MonthlyRevenueDto.builder()
                    .label(label)
                    .revenue(row != null ? (BigDecimal) row[2] : BigDecimal.ZERO)
                    .orderCount(row != null ? ((Number) row[3]).longValue() : 0L)
                    .build());

            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    /** Order status counts excluding CART */
    private Map<String, Long> buildStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        // initialize all statuses to 0
        for (OrderStatus s : OrderStatus.values()) {
            if (s != OrderStatus.CART) counts.put(s.getLabel(), 0L);
        }
        orderRepository.countGroupByStatus().forEach(row -> {
            OrderStatus s = (OrderStatus) row[0];
            counts.put(s.getLabel(), ((Number) row[1]).longValue());
        });
        return counts;
    }

    private List<DashboardResDto.RecentOrderDto> buildRecentOrders() {
        return orderRepository.findRecent(PageRequest.of(0, 8))
                .stream()
                .map(DashboardResDto.RecentOrderDto::new)
                .collect(Collectors.toList());
    }

    private List<DashboardResDto.TopProductDto> buildTopProducts() {
        return orderDetailRepository.findTopSellingProducts(PageRequest.of(0, 5))
                .stream()
                .map(row -> DashboardResDto.TopProductDto.builder()
                        .id((Long) row[0])
                        .name((String) row[1])
                        .totalQuantity(((Number) row[2]).longValue())
                        .build())
                .collect(Collectors.toList());
    }
}
