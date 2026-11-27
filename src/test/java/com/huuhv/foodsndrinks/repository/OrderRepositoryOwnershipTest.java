package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.projection.OrderStatusView;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chống IDOR ở chính câu truy vấn (MySQL thật): hai truy vấn trạng thái đơn dùng cho realtime chỉ trả đơn của đúng chủ
 * đơn và không bao giờ trả giỏ hàng; danh sách đơn của admin không bao giờ có giỏ hàng — unit test của service chỉ kiểm
 * tra tham số truyền xuống, không kiểm tra JPQL.
 */
@IntegrationTest
@Transactional
class OrderRepositoryOwnershipTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;

    private User alice;
    private User bob;
    private Order aliceOrder;
    private Order aliceCart;
    private Order bobOrder;

    @BeforeEach
    void data() {
        alice = user("alice");
        bob = user("bob");
        aliceOrder = order(alice, OrderStatus.PROCESSING);
        aliceCart = order(alice, OrderStatus.CART);
        bobOrder = order(bob, OrderStatus.PENDING);
    }

    @Test
    void statusesForUser_returnOnlyOwnPlacedOrders() {
        List<OrderStatusView> statuses = orderRepository.findStatusesByUserIdAndIdIn(alice.getId(),
                List.of(aliceOrder.getId(), aliceCart.getId(), bobOrder.getId(), Long.MAX_VALUE));

        assertThat(statuses).extracting(OrderStatusView::getId).containsExactly(aliceOrder.getId());
        assertThat(statuses).extracting(OrderStatusView::getStatus).containsExactly(OrderStatus.PROCESSING);
        assertThat(orderRepository.findStatusesByUserIdAndIdIn(bob.getId(), List.of(aliceOrder.getId()))).isEmpty();
    }

    @Test
    void statusForOwner_isFoundOnlyWithTheOwnersUsername() {
        assertThat(orderRepository.findStatusByIdAndOwnerUsername(aliceOrder.getId(), alice.getUsername()))
                .contains(OrderStatus.PROCESSING);
        // Message giả/nhầm chủ đơn: không lộ trạng thái đơn của người khác
        assertThat(orderRepository.findStatusByIdAndOwnerUsername(aliceOrder.getId(), bob.getUsername())).isEmpty();
        assertThat(orderRepository.findStatusByIdAndOwnerUsername(Long.MAX_VALUE, alice.getUsername())).isEmpty();
    }

    @Test
    void adminSearch_neverReturnsCarts_evenWhenFilteringByCart() {
        // Lọc theo tên chủ đơn để không phụ thuộc dữ liệu của class test khác trong cùng DB
        Page<Order> all = orderRepository.search(null, alice.getUsername(), null, null, null, PageRequest.of(0, 20));
        assertThat(all.getContent()).extracting(Order::getId).containsExactly(aliceOrder.getId());
        assertThat(all.getTotalElements()).isEqualTo(1);

        Page<Order> carts = orderRepository.search(OrderStatus.CART, alice.getUsername(), null, null, null,
                PageRequest.of(0, 20));
        assertThat(carts.getContent()).isEmpty();
        assertThat(carts.getTotalElements()).isZero();

        assertThat(orderRepository.search(null, null, aliceCart.getId(), null, null, PageRequest.of(0, 20)).getContent())
                .isEmpty();
    }

    private User user(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("{noop}not-used")
                .fullName("Ownership Test")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
    }

    private Order order(User owner, OrderStatus status) {
        return orderRepository.saveAndFlush(Order.builder()
                .user(owner)
                .status(status)
                .totalPrice(new BigDecimal("50000"))
                .shippingAddress("12 Lê Lợi")
                .orderedAt(status == OrderStatus.CART ? null : LocalDateTime.now())
                .build());
    }
}
