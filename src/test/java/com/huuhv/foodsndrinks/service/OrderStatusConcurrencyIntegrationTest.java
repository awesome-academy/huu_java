package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.ConcurrentUpdateException;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hai admin đổi trạng thái cùng một đơn (issue #36) trên MySQL thật, hai transaction thật trên hai thread.
 * <p>
 * Không {@code @Transactional}: mỗi "admin" là một transaction riêng, commit thật; test tự xoá dữ liệu của nó. Cuộc đua
 * được dựng XÁC ĐỊNH (không trông vào may rủi của lịch thread): mỗi thread mở transaction, đọc đơn (cả hai thấy
 * {@code PENDING}), chờ nhau ở barrier rồi mới gọi {@code updateStatus} — đúng tình huống "hai người cùng mở trang rồi
 * cùng bấm lưu".
 */
@IntegrationTest
class OrderStatusConcurrencyIntegrationTest {

    private static final String CONFLICT_MESSAGE = "Đơn hàng vừa được người khác cập nhật, vui lòng tải lại trang";

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ExecutorService admins = Executors.newFixedThreadPool(2);
    private User owner;
    private Order order;

    @BeforeEach
    void data() {
        String username = "race-" + UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("{noop}not-used")
                .fullName("Race Test")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
        order = orderRepository.saveAndFlush(Order.builder()
                .user(owner)
                .status(OrderStatus.PENDING)
                .totalPrice(new BigDecimal("50000"))
                .shippingAddress("12 Lê Lợi")
                .orderedAt(LocalDateTime.now())
                .build());
    }

    @AfterEach
    void cleanUp() {
        admins.shutdownNow();
        jdbcTemplate.update("DELETE FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?", order.getId());
        orderRepository.deleteById(order.getId());
        userRepository.deleteById(owner.getId());
    }

    @Test
    void conditionalUpdate_changesTheRowOnlyWhileItStillHasTheExpectedStatus() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Integer first = tx.execute(s ->
                orderRepository.updateStatusIfCurrent(order.getId(), OrderStatus.PENDING, OrderStatus.COMPLETED));
        // "from" đã cũ (đơn đang COMPLETED) → không đổi dòng nào, trạng thái giữ nguyên
        Integer stale = tx.execute(s ->
                orderRepository.updateStatusIfCurrent(order.getId(), OrderStatus.PENDING, OrderStatus.CANCELLED));

        assertThat(first).isEqualTo(1);
        assertThat(stale).isZero();
        assertThat(statusInDb()).isEqualTo("COMPLETED");
        // Chỉ cột status đổi: thời điểm đặt còn nguyên
        assertThat(jdbcTemplate.queryForObject("SELECT ordered_at IS NOT NULL FROM orders WHERE id = ?",
                Boolean.class, order.getId())).isTrue();
    }

    @Test
    void twoAdminsChangingTheSameOrder_onlyTheFirstWins_andOnlyThatTransitionIsAuditedAndPublished() throws Exception {
        CyclicBarrier bothHaveReadTheOrder = new CyclicBarrier(2);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            Future<Void> completes = admins.submit(admin(OrderStatus.COMPLETED, bothHaveReadTheOrder));
            Future<Void> cancels = admins.submit(admin(OrderStatus.CANCELLED, bothHaveReadTheOrder));

            Throwable completeFailure = failureOf(completes);
            Throwable cancelFailure = failureOf(cancels);

            // Đúng một request thành công; request kia bị từ chối với thông báo cho người dùng, không ghi đè
            assertThat(List.of(completeFailure == null, cancelFailure == null)).containsExactlyInAnyOrder(true, false);
            Throwable loser = completeFailure != null ? completeFailure : cancelFailure;
            assertThat(loser).isInstanceOf(ConcurrentUpdateException.class).hasMessage(CONFLICT_MESSAGE);

            String winner = completeFailure == null ? "COMPLETED" : "CANCELLED";
            assertThat(statusInDb()).isEqualTo(winner);

            // Audit chỉ ghi lần chuyển trạng thái thật sự xảy ra, "from" đúng với DB lúc đổi
            assertThat(audit.lines()).filteredOn(line -> line.contains("order.id=" + order.getId()))
                    .singleElement().asString()
                    .contains("event.action=ORDER_STATUS_CHANGED", "order.status.from=PENDING",
                            "order.status.to=" + winner);
            // ... và chỉ lần đó được báo cho chủ đơn/admin (một bản ghi outbox)
            assertThat(jdbcTemplate.queryForList(
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.status')) FROM outbox_messages"
                            + " WHERE JSON_EXTRACT(payload, '$.orderId') = ?", String.class, order.getId()))
                    .containsExactly(winner);
        }
    }

    @Test
    void sequentialChanges_bothSucceed_eachAuditedWithTheStatusItReallyReplaced() {
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            orderService.updateStatus(order.getId(), OrderStatus.PROCESSING);
            orderService.updateStatus(order.getId(), OrderStatus.COMPLETED);

            assertThat(statusInDb()).isEqualTo("COMPLETED");
            assertThat(audit.lines()).filteredOn(line -> line.contains("order.id=" + order.getId()))
                    .hasSize(2)
                    .satisfiesExactly(
                            first -> assertThat(first).contains("order.status.from=PENDING", "order.status.to=PROCESSING"),
                            second -> assertThat(second)
                                    .contains("order.status.from=PROCESSING", "order.status.to=COMPLETED"));
        }
    }

    @Test
    void cartIsNeverTouchedByTheConditionalPath() {
        assertThatThrownBy(() -> orderService.updateStatus(order.getId(), OrderStatus.CART))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(statusInDb()).isEqualTo("PENDING");
    }

    /**
     * Một admin: mở transaction → đọc đơn (chốt snapshot REPEATABLE READ, cả hai cùng thấy PENDING) → chờ admin kia cũng
     * đọc xong → đổi trạng thái trong CHÍNH transaction đó → commit.
     */
    private Callable<Void> admin(OrderStatus target, CyclicBarrier bothHaveReadTheOrder) {
        return () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(orderRepository.findByIdWithUser(order.getId())).get()
                        .extracting(Order::getStatus).isEqualTo(OrderStatus.PENDING);
                await(bothHaveReadTheOrder);
                orderService.updateStatus(order.getId(), target);
            });
            return null;
        };
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("The other admin never read the order", ex);
        }
    }

    /** {@code null} nếu request thành công, ngược lại là exception nó ném ra. */
    private static Throwable failureOf(Future<Void> request) throws Exception {
        try {
            request.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }

    private String statusInDb() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.getId());
    }
}
