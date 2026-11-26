package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        when(orderRepository.sumCompletedRevenue()).thenReturn(new BigDecimal("150000"));
        when(productRepository.countByIsAvailableTrue()).thenReturn(12L);
        when(userRepository.countByIsActiveTrue()).thenReturn(7L);
        when(orderRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{OrderStatus.PENDING, 2L},
                new Object[]{OrderStatus.PROCESSING, 1L},
                new Object[]{OrderStatus.COMPLETED, 3L}));
        when(orderRepository.findRecent(any())).thenReturn(List.of(recentOrder()));
        when(orderDetailRepository.findTopSellingProducts(any())).thenReturn(List.<Object[]>of(
                new Object[]{7L, "Trà sữa", 12L}));

        DashboardResDto result = dashboardService.getDashboardData();

        assertThat(result.getTotalRevenue()).isEqualByComparingTo("150000");
        assertThat(result.getTotalProducts()).isEqualTo(12L);
        assertThat(result.getTotalUsers()).isEqualTo(7L);

        // Mọi trạng thái trừ CART, khởi tạo 0
        assertThat(result.getStatusCounts()).containsExactly(
                Map.entry(OrderStatus.PENDING.getLabel(), 2L),
                Map.entry(OrderStatus.PROCESSING.getLabel(), 1L),
                Map.entry(OrderStatus.COMPLETED.getLabel(), 3L),
                Map.entry(OrderStatus.CANCELLED.getLabel(), 0L));
        // KPI suy ra từ cùng kết quả đếm → luôn khớp bảng trạng thái, không tốn truy vấn riêng
        assertThat(result.getTotalCompletedOrders()).isEqualTo(3L);
        assertThat(result.getActiveOrders()).isEqualTo(3L);   // PENDING + PROCESSING
        // Biểu đồ doanh thu theo tháng chỉ tính ở getChartData
        verify(orderRepository, never()).findMonthlyRevenue(any());

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
        when(userRepository.countByIsActiveTrue()).thenAnswer(inv -> {
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

    @Test
    void getDashboardData_rejectsImmediately_whenExecutorIsSaturated() {
        // Executor đầy (AbortPolicy) → không chạy truy vấn nào trên thread request, báo 503 ngay
        Executor saturated = task -> {
            throw new TaskRejectedException("dashboard pool is full");
        };
        AsyncProperties properties = new AsyncProperties(
                new AsyncProperties.Pool(2, 4, 100),
                new AsyncProperties.DashboardPool(4, 4, 50, Duration.ofSeconds(30)));
        DashboardService service = new DashboardService(orderRepository, orderDetailRepository, productRepository,
                userRepository, saturated, transactionManager, properties);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(service::getDashboardData)
                        .isInstanceOf(DashboardUnavailableException.class)
                        .hasCauseInstanceOf(RejectedExecutionException.class));
        verifyNoInteractions(orderRepository, orderDetailRepository, productRepository, userRepository);
    }

    @Test
    void getChartData_returnsSixMonths_andStatusCounts() {
        LocalDate now = LocalDate.now();
        when(orderRepository.findMonthlyRevenue(any())).thenReturn(List.<Object[]>of(
                new Object[]{now.getYear(), now.getMonthValue(), new BigDecimal("150000"), 3L}));
        when(orderRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{OrderStatus.PENDING, 2L}));

        DashboardChartResDto charts = dashboardService.getChartData();

        // 6 tháng, tháng không có dữ liệu = 0, tháng hiện tại ở cuối
        assertThat(charts.monthLabels()).hasSize(6)
                .last().isEqualTo("Th" + now.getMonthValue() + "/" + now.getYear());
        assertThat(charts.monthRevenues().subList(0, 5))
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(charts.monthRevenues().get(5)).isEqualByComparingTo("150000");
        assertThat(charts.statusLabels()).containsExactly(OrderStatus.PENDING.getLabel(),
                OrderStatus.PROCESSING.getLabel(), OrderStatus.COMPLETED.getLabel(), OrderStatus.CANCELLED.getLabel());
        assertThat(charts.statusCounts()).containsExactly(2L, 0L, 0L, 0L);
    }

    @Test
    void constructor_injectsDashboardExecutorByQualifier() {
        // @RequiredArgsConstructor chỉ copy @Qualifier sang tham số khi lombok.config có lombok.copyableAnnotations
        Parameter executorParam = Arrays.stream(DashboardService.class.getDeclaredConstructors())
                .flatMap(c -> Arrays.stream(c.getParameters()))
                .filter(p -> p.getType() == Executor.class)
                .findFirst().orElseThrow();

        assertThat(executorParam.getAnnotation(Qualifier.class))
                .isNotNull()
                .extracting(Qualifier::value).isEqualTo(AsyncProperties.DASHBOARD_EXECUTOR);
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
