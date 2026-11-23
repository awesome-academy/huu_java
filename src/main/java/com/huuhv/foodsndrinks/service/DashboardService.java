package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.config.AsyncConfig;
import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.dto.response.DashboardResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.repository.OrderDetailRepository;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final TransactionDefinition READ_ONLY;

    static {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setReadOnly(true);
        READ_ONLY = definition;
    }

    private final OrderRepository            orderRepository;
    private final OrderDetailRepository      orderDetailRepository;
    private final ProductRepository          productRepository;
    private final UserRepository             userRepository;
    // @Qualifier được Lombok copy sang tham số constructor (lombok.config: lombok.copyableAnnotations)
    @Qualifier(AsyncConfig.DASHBOARD_EXECUTOR)
    private final Executor                   dashboardExecutor;
    private final PlatformTransactionManager transactionManager;
    private final AsyncProperties            asyncProperties;

    /**
     * Các truy vấn độc lập chạy song song trên {@code dashboardExecutor}, gộp lại với timeout
     * ({@code app.async.dashboard.timeout}).
     * <p>
     * Cố ý KHÔNG {@code @Transactional}: thread request giữ một connection trong lúc chờ các worker, mỗi worker lại cần
     * một connection → nhiều admin cùng mở dashboard có thể làm cạn pool Hikari. Mỗi truy vấn tự mở transaction
     * read-only của nó và map sang DTO ngay trong transaction đó (không đưa entity ra khỏi thread worker).
     * <p>
     * Số đơn hoàn thành/đang xử lý tính từ cùng kết quả đếm theo trạng thái nên luôn khớp bảng trạng thái. Các truy vấn
     * khác (doanh thu, sản phẩm...) chạy ở transaction riêng nên có thể lệch nhau vài ms — chấp nhận được với dashboard.
     * Biểu đồ doanh thu theo tháng không tính ở đây: {@code dashboard.js} lấy qua {@link #getChartData()}.
     *
     * @throws DashboardUnavailableException khi một truy vấn lỗi, quá thời gian hoặc pool {@code dashboard-*} đầy
     */
    public DashboardResDto getDashboardData() {
        long start = System.nanoTime();

        CompletableFuture<BigDecimal>              revenue  = query(orderRepository::sumCompletedRevenue);
        CompletableFuture<Long>                    products = query(productRepository::countByIsAvailableTrue);
        CompletableFuture<Long>                    users    = query(userRepository::countByIsActiveTrue);
        CompletableFuture<Map<OrderStatus, Long>>  status   = query(this::countByStatus);
        CompletableFuture<List<DashboardResDto.RecentOrderDto>> recent = query(this::buildRecentOrders);
        CompletableFuture<List<DashboardResDto.TopProductDto>>  top    = query(this::buildTopProducts);

        awaitAll(revenue, products, users, status, recent, top);

        Map<OrderStatus, Long> counts = status.join();
        DashboardResDto result = DashboardResDto.builder()
                .totalRevenue(revenue.join())
                .totalCompletedOrders(counts.get(OrderStatus.COMPLETED))
                .activeOrders(counts.get(OrderStatus.PENDING) + counts.get(OrderStatus.PROCESSING))
                .totalProducts(products.join())
                .totalUsers(users.join())
                .statusCounts(toLabels(counts))
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
        Map<String, Long> statusCounts = toLabels(countByStatus());
        return new DashboardChartResDto(
                monthly.stream().map(DashboardResDto.MonthlyRevenueDto::getLabel).toList(),
                monthly.stream().map(DashboardResDto.MonthlyRevenueDto::getRevenue).toList(),
                List.copyOf(statusCounts.keySet()),
                List.copyOf(statusCounts.values()));
    }

    // -------------------------------------------------------
    // Parallel helpers
    // -------------------------------------------------------

    /**
     * Chạy {@code supplier} trên {@code dashboardExecutor}, trong transaction read-only riêng của thread worker. Pool đầy
     * ({@code AbortPolicy}, xem {@code AsyncConfig}) thì trả future lỗi: truy vấn không bao giờ chạy trên thread request
     * (ngoài timeout, giữ connection tới hết request vì Open Session in View) — {@link #awaitAll} báo 503 ngay.
     */
    private <T> CompletableFuture<T> query(Supplier<T> supplier) {
        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager, READ_ONLY);
        try {
            return CompletableFuture.supplyAsync(() -> readOnlyTx.execute(status -> supplier.get()), dashboardExecutor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Chờ mọi truy vấn, tối đa {@code app.async.dashboard.timeout}. Truy vấn nào lỗi thì báo lỗi ngay (không chờ các
     * truy vấn còn lại); quá thời gian thì huỷ các future chưa xong (truy vấn JDBC đang chạy vẫn tự kết thúc, kết quả bị bỏ).
     */
    private void awaitAll(CompletableFuture<?>... futures) {
        Duration timeout = asyncProperties.dashboard().timeout();
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
            // cancel cả future còn trong hàng đợi: task của nó thấy future đã xong nên không chạy truy vấn
            cancelAll(futures);
            if (ex.getCause() instanceof RejectedExecutionException) {
                log.warn("Dashboard executor is saturated, rejecting dashboard request");
                throw new DashboardUnavailableException("Dashboard executor is saturated", ex.getCause());
            }
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

    /** Số đơn theo trạng thái (trừ CART), trạng thái không có đơn = 0. */
    private Map<OrderStatus, Long> countByStatus() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            if (s != OrderStatus.CART) counts.put(s, 0L);
        }
        orderRepository.countGroupByStatus().forEach(row ->
                counts.put((OrderStatus) row[0], ((Number) row[1]).longValue()));
        return counts;
    }

    /** Key là nhãn tiếng Việt cho view/chart, giữ thứ tự khai báo của enum. */
    private static Map<String, Long> toLabels(Map<OrderStatus, Long> counts) {
        Map<String, Long> labels = new LinkedHashMap<>();
        counts.forEach((status, count) -> labels.put(status.getLabel(), count));
        return labels;
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
