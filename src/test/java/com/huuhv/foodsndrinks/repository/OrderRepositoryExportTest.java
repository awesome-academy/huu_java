package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.OrderDetail;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.projection.OrderExportRow;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truy vấn export đơn hàng trên MySQL thật: projection (kể cả subquery "số món"), bộ lọc ngày đặt, và file export luôn
 * là đúng danh sách admin đang lọc ({@code search}) — unit test của service chỉ kiểm tra tham số truyền xuống.
 */
@IntegrationTest
@Transactional
class OrderRepositoryExportTest {

    private static final LocalDateTime NOV_10 = LocalDateTime.of(2026, 11, 10, 0, 0);
    private static final Limit NO_LIMIT = Limit.of(100);

    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;

    private User alice;
    private Order early;
    private Order atStartOfDay;
    private Order lateSameDay;
    private Order nextDay;

    @BeforeEach
    void data() {
        alice = user();
        Product tea = product();
        early = order(OrderStatus.COMPLETED, NOV_10.minusSeconds(1));
        atStartOfDay = order(OrderStatus.PENDING, NOV_10);
        lateSameDay = order(OrderStatus.PENDING, NOV_10.plusHours(23).plusMinutes(59));
        nextDay = order(OrderStatus.CANCELLED, NOV_10.plusDays(1));
        order(OrderStatus.CART, null);
        line(atStartOfDay, tea, 2);
        line(atStartOfDay, tea, 3);
        line(lateSameDay, tea, 1);
        orderRepository.flush();
    }

    @Test
    void exportRows_carryCustomerStatusTotalAndSummedItemCount_newestFirst() {
        List<OrderExportRow> rows = export(null, null, null);

        // Lọc theo username (duy nhất của test này) để không phụ thuộc dữ liệu của class test khác; không có giỏ hàng
        assertThat(rows).extracting(OrderExportRow::id)
                .containsExactly(nextDay.getId(), lateSameDay.getId(), atStartOfDay.getId(), early.getId());

        OrderExportRow row = rows.get(2);
        assertThat(row.customerFullName()).isEqualTo("Export Test");
        assertThat(row.customerUsername()).isEqualTo(alice.getUsername());
        assertThat(row.orderedAt()).isEqualTo(NOV_10);
        assertThat(row.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(row.totalPrice()).isEqualByComparingTo("50000");
        // Số món = tổng quantity của các dòng chi tiết; đơn không có dòng nào → 0
        assertThat(row.itemCount()).isEqualTo(5L);
        assertThat(rows.get(1).itemCount()).isEqualTo(1L);
        assertThat(rows.get(0).itemCount()).isZero();
    }

    @Test
    void orderedDateRange_includesTheStart_andExcludesTheUpperBound() {
        assertThat(export(null, NOV_10, NOV_10.plusDays(1))).extracting(OrderExportRow::id)
                .containsExactly(lateSameDay.getId(), atStartOfDay.getId());
        assertThat(export(null, NOV_10.plusDays(1), null)).extracting(OrderExportRow::id)
                .containsExactly(nextDay.getId());
        assertThat(export(null, null, NOV_10)).extracting(OrderExportRow::id).containsExactly(early.getId());
    }

    @Test
    void exportRows_matchTheAdminList_forTheSameFilter() {
        List<Long> listed = orderRepository
                .search(OrderStatus.PENDING, alice.getUsername(), null, NOV_10, NOV_10.plusDays(1), PageRequest.of(0, 50))
                .map(Order::getId).getContent();

        assertThat(export(OrderStatus.PENDING, NOV_10, NOV_10.plusDays(1))).extracting(OrderExportRow::id)
                .containsExactlyElementsOf(listed)
                .containsExactly(lateSameDay.getId(), atStartOfDay.getId());
        // Lọc theo mã đơn của danh sách cũng áp dụng cho file export
        assertThat(orderRepository.findExportRows(null, null, early.getId(), null, null, NO_LIMIT))
                .extracting(OrderExportRow::id).containsExactly(early.getId());
        // Giỏ hàng không bao giờ được xuất, kể cả khi lọc theo CART
        assertThat(export(OrderStatus.CART, null, null)).isEmpty();
    }

    @Test
    void limit_capsTheNumberOfRowsRead() {
        assertThat(orderRepository.findExportRows(null, alice.getUsername(), null, null, null, Limit.of(2)))
                .extracting(OrderExportRow::id).containsExactly(nextDay.getId(), lateSameDay.getId());
    }

    private List<OrderExportRow> export(OrderStatus status, LocalDateTime from, LocalDateTime before) {
        return orderRepository.findExportRows(status, alice.getUsername(), null, from, before, NO_LIMIT);
    }

    private User user() {
        String username = "export-" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("{noop}not-used")
                .fullName("Export Test")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
    }

    private Product product() {
        String name = "Export tea " + UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product();
        product.setName(name);
        product.setSlug(name.toLowerCase().replace(' ', '-'));
        product.setType(ProductType.DRINK);
        product.setPrice(new BigDecimal("10000"));
        product.setIsAvailable(true);
        return productRepository.saveAndFlush(product);
    }

    private Order order(OrderStatus status, LocalDateTime orderedAt) {
        return orderRepository.saveAndFlush(Order.builder()
                .user(alice)
                .status(status)
                .totalPrice(new BigDecimal("50000"))
                .shippingAddress("12 Lê Lợi")
                .orderedAt(orderedAt)
                .build());
    }

    private void line(Order order, Product product, int quantity) {
        OrderDetail detail = new OrderDetail();
        detail.setOrder(order);
        detail.setProduct(product);
        detail.setQuantity(quantity);
        detail.setUnitPrice(product.getPrice());
        detail.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.getOrderDetails().add(detail);
        orderRepository.saveAndFlush(order);
    }
}
