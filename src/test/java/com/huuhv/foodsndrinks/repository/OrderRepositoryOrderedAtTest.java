package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.projection.OrderExportRow;
import com.huuhv.foodsndrinks.service.DashboardService;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giỏ hàng là một {@code Order} nên {@code created_at} (và {@code id}) là lúc TẠO GIỎ; thời điểm đặt là
 * {@code ordered_at}. Trên MySQL thật: doanh thu theo tháng, "đơn gần đây", danh sách đơn của admin, file export và lịch
 * sử đơn của khách đều tính/sắp xếp theo {@code ordered_at} (issue #41).
 */
@IntegrationTest
@Transactional
class OrderRepositoryOrderedAtTest {

    /** Tháng không có dữ liệu của test khác (DB dùng chung giữa các class test). */
    private static final YearMonth FEB_2001 = YearMonth.of(2001, 2);
    /** Sau mọi đơn do test khác tạo ra (dùng {@code now()}), trong giới hạn 2038 của TIMESTAMP. */
    private static final LocalDateTime FAR_FUTURE = LocalDateTime.of(2037, 6, 1, 12, 0);

    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DashboardService dashboardService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User alice;

    @BeforeEach
    void data() {
        String username = "ordered-" + UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("{noop}not-used")
                .fullName("Ordered At Test")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
    }

    @Test
    void monthlyRevenue_countsOrderInTheMonthItWasPlaced_notTheMonthTheCartWasCreated() {
        // Giỏ tạo ngày 20/1, đặt 00:30 ngày 1/2 giờ máy chủ. Với JVM ở +07:00, DB (UTC) lưu 17:30 ngày 31/1 —
        // YEAR()/MONTH() của MySQL sẽ xếp đơn này vào tháng 1; khoảng [from, to) do Java truyền vào thì không.
        Order order = order(OrderStatus.COMPLETED, FEB_2001.atDay(1).atTime(0, 30), "120000");
        setCreatedAt(order, FEB_2001.minusMonths(1).atDay(20).atTime(10, 0));
        // Đơn chưa hoàn thành và giỏ hàng không phải doanh thu
        order(OrderStatus.PENDING, FEB_2001.atDay(2).atTime(9, 0), "999999");
        order(OrderStatus.CART, null, "999999");

        assertThat(revenueOf(FEB_2001)).isEqualByComparingTo("120000");
        assertThat(revenueOf(FEB_2001.minusMonths(1))).isEqualByComparingTo("0");
        assertThat(revenueOf(FEB_2001.plusMonths(1))).isEqualByComparingTo("0");
    }

    @Test
    void monthlyRevenue_excludesTheFirstInstantOfTheNextMonth() {
        order(OrderStatus.COMPLETED, FEB_2001.atEndOfMonth().atTime(23, 59, 59), "70000");
        order(OrderStatus.COMPLETED, FEB_2001.plusMonths(1).atDay(1).atStartOfDay(), "30000");

        assertThat(revenueOf(FEB_2001)).isEqualByComparingTo("70000");
        assertThat(revenueOf(FEB_2001.plusMonths(1))).isEqualByComparingTo("30000");
    }

    @Test
    void dashboardChart_addsRevenueToTheCurrentMonth_whenCartWasCreatedLastMonth() {
        DashboardChartResDto before = dashboardService.getChartData();

        Order order = order(OrderStatus.COMPLETED, LocalDateTime.now(), "250000");
        setCreatedAt(order, YearMonth.now().minusMonths(1).atDay(15).atTime(8, 0));

        DashboardChartResDto after = dashboardService.getChartData();
        int thisMonth = after.monthRevenues().size() - 1;   // tháng hiện tại ở cuối
        assertThat(after.monthRevenues().get(thisMonth).subtract(before.monthRevenues().get(thisMonth)))
                .isEqualByComparingTo("250000");
        // Tháng trước (tháng tạo giỏ) không đổi
        assertThat(after.monthRevenues().get(thisMonth - 1))
                .isEqualByComparingTo(before.monthRevenues().get(thisMonth - 1));
    }

    @Test
    void recentOrders_areSortedByOrderedAt_notByCartCreation() {
        // "oldCart" được tạo trước (id nhỏ hơn, created_at sớm hơn) nhưng ĐẶT sau → phải đứng đầu
        Order oldCart = order(OrderStatus.PENDING, FAR_FUTURE.plusDays(1), "10000");
        Order newCart = order(OrderStatus.PENDING, FAR_FUTURE, "10000");
        setCreatedAt(oldCart, LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(orderRepository.findRecent(PageRequest.of(0, 2))).extracting(Order::getId)
                .containsExactly(oldCart.getId(), newCart.getId());
    }

    @Test
    void adminList_export_andOrderHistory_areSortedByOrderedAt_thenIdForTies() {
        Order placedLast = order(OrderStatus.PENDING, FAR_FUTURE.plusHours(2), "10000");
        Order tieFirstId = order(OrderStatus.PENDING, FAR_FUTURE, "10000");
        Order tieSecondId = order(OrderStatus.COMPLETED, FAR_FUTURE, "10000");
        order(OrderStatus.CART, null, "10000");

        Long[] expected = {placedLast.getId(), tieSecondId.getId(), tieFirstId.getId()};
        assertThat(orderRepository.search(null, alice.getUsername(), null, null, null, PageRequest.of(0, 10)))
                .extracting(Order::getId).containsExactly(expected);
        assertThat(orderRepository.findExportRows(null, alice.getUsername(), null, null, null, Limit.of(10)))
                .extracting(OrderExportRow::id).containsExactly(expected);
        assertThat(orderRepository.findOrderHistoryByUserId(alice.getId(), PageRequest.of(0, 10)))
                .extracting(Order::getId).containsExactly(expected);
    }

    private BigDecimal revenueOf(YearMonth month) {
        return orderRepository.sumCompletedRevenueBetween(
                month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
    }

    private Order order(OrderStatus status, LocalDateTime orderedAt, String total) {
        return orderRepository.saveAndFlush(Order.builder()
                .user(alice)
                .status(status)
                .totalPrice(new BigDecimal(total))
                .shippingAddress("12 Lê Lợi")
                .orderedAt(orderedAt)
                .build());
    }

    /** {@code created_at} là {@code @CreationTimestamp}, {@code updatable = false} → chỉ sửa được bằng SQL. */
    private void setCreatedAt(Order order, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), order.getId());
    }
}
