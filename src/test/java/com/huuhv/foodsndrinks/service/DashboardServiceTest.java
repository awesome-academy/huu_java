package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.config.AsyncProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardResDto;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.repository.OrderDetailRepository;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link DashboardService#getDashboardData()} chạy các truy vấn song song trên một pool thật (không khởi động Spring),
 * repository là mock, transaction manager là mock ({@code TransactionTemplate} chỉ gọi begin/commit/rollback trên nó).
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Mock private OrderRepository orderRepository;
    @Mock private OrderDetailRepository orderDetailRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private ThreadPoolTaskExecutor executor;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("dashboard-");
        executor.initialize();

        AsyncProperties properties = new AsyncProperties(
                new AsyncProperties.Pool(2, 4, 100),
                new AsyncProperties.DashboardPool(4, 4, 50, TIMEOUT));
        dashboardService = new DashboardService(orderRepository, orderDetailRepository, productRepository,
                userRepository, executor, transactionManager, properties);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void getDashboardData_mergesResultsOfAllQueries() {
        LocalDate now = LocalDate.now();
        when(orderRepository.sumCompletedRevenue()).thenReturn(new BigDecimal("150000"));
        when(orderRepository.countByStatus(OrderStatus.COMPLETED)).thenReturn(3L);
        when(orderRepository.countActiveOrders()).thenReturn(2L);
        when(productRepository.countByIsAvailableTrue()).thenReturn(12L);
        when(userRepository.countByIsActiveTrue()).thenReturn(7L);
        when(orderRepository.findMonthlyRevenue(any())).thenReturn(List.<Object[]>of(
                new Object[]{now.getYear(), now.getMonthValue(), new BigDecimal("150000"), 3L}));
        when(orderRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{OrderStatus.PENDING, 2L}));
        when(orderRepository.findRecent(any())).thenReturn(List.of(recentOrder()));
        when(orderDetailRepository.findTopSellingProducts(any())).thenReturn(List.<Object[]>of(
                new Object[]{7L, "Trà sữa", 12L}));

        DashboardResDto result = dashboardService.getDashboardData();

        assertThat(result.getTotalRevenue()).isEqualByComparingTo("150000");
        assertThat(result.getTotalCompletedOrders()).isEqualTo(3L);
        assertThat(result.getActiveOrders()).isEqualTo(2L);
        assertThat(result.getTotalProducts()).isEqualTo(12L);
        assertThat(result.getTotalUsers()).isEqualTo(7L);

        // 6 tháng, tháng không có dữ liệu = 0, tháng hiện tại ở cuối
        assertThat(result.getMonthlyRevenue()).hasSize(6);
        assertThat(result.getMonthlyRevenue().subList(0, 5))
                .allSatisfy(m -> assertThat(m.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO));
        DashboardResDto.MonthlyRevenueDto current = result.getMonthlyRevenue().get(5);
        assertThat(current.getLabel()).isEqualTo("Th" + now.getMonthValue() + "/" + now.getYear());
        assertThat(current.getRevenue()).isEqualByComparingTo("150000");
        assertThat(current.getOrderCount()).isEqualTo(3L);

        // Mọi trạng thái trừ CART, khởi tạo 0
        assertThat(result.getStatusCounts()).containsExactly(
                Map.entry(OrderStatus.PENDING.getLabel(), 2L),
                Map.entry(OrderStatus.PROCESSING.getLabel(), 0L),
                Map.entry(OrderStatus.COMPLETED.getLabel(), 0L),
                Map.entry(OrderStatus.CANCELLED.getLabel(), 0L));

        assertThat(result.getRecentOrders()).singleElement().satisfies(o -> {
            assertThat(o.getId()).isEqualTo(42L);
            assertThat(o.getUsername()).isEqualTo("alice");
        });
        assertThat(result.getTopProducts()).singleElement().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("Trà sữa");
            assertThat(p.getTotalQuantity()).isEqualTo(12L);
        });
    }

    @Test
    void getDashboardData_runsQueriesOnDashboardExecutor() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        when(orderRepository.sumCompletedRevenue()).thenAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            return BigDecimal.ONE;
        });
        when(orderRepository.countActiveOrders()).thenAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            return 1L;
        });

        dashboardService.getDashboardData();

        assertThat(threads).isNotEmpty().allSatisfy(name -> assertThat(name).startsWith("dashboard-"));
    }

    @Test
    void getDashboardData_failsFast_whenOneQueryFails() {
        IllegalStateException dbDown = new IllegalStateException("db down");
        when(orderDetailRepository.findTopSellingProducts(any())).thenThrow(dbDown);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> dashboardService.getDashboardData())
                        .isInstanceOf(DashboardUnavailableException.class)
                        .hasCause(dbDown));
    }

    @Test
    void getDashboardData_doesNotHang_whenQueryExceedsTimeout() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        when(orderRepository.countGroupByStatus()).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });

        try {
            long start = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(() -> dashboardService.getDashboardData())
                            .isInstanceOf(DashboardUnavailableException.class)
                            .hasMessageContaining("timed out"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(TIMEOUT);
        } finally {
            release.countDown();
        }
    }

    private static Order recentOrder() {
        User user = User.builder().username("alice").fullName("Alice").build();
        return Order.builder()
                .id(42L)
                .user(user)
                .totalPrice(new BigDecimal("50000"))
                .status(OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
