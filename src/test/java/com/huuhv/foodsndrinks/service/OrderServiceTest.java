package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.OrderDetail;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.OrderDetailRepository;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.ProductImageRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.projection.OrderStatusView;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import com.huuhv.foodsndrinks.service.messaging.OutboxType;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test {@link OrderService}: giỏ hàng (kể cả chống IDOR), checkout và đổi trạng thái đơn — không khởi động Spring. */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderDetailRepository orderDetailRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private OutboxService outboxService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private OrderService orderService;

    private final User alice = user(1L, "alice");
    private final User mallory = user(2L, "mallory");

    private static User user(Long id, String username) {
        return User.builder().id(id).username(username).fullName(username).email(username + "@example.com").build();
    }

    private static Product product(Long id, String price, boolean available) {
        return Product.builder().id(id).name("Product " + id).price(new BigDecimal(price)).isAvailable(available).build();
    }

    private static Order cartOf(User owner, OrderStatus status) {
        return Order.builder().id(100L).user(owner).status(status).orderDetails(new ArrayList<>()).build();
    }

    private static OrderDetail line(Long id, Order order, Product product, int quantity) {
        OrderDetail detail = OrderDetail.builder().id(id).order(order).product(product).quantity(quantity)
                .unitPrice(product.getPrice())
                .subtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .build();
        order.getOrderDetails().add(detail);
        order.setTotalPrice(order.getOrderDetails().stream()
                .map(OrderDetail::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        return detail;
    }

    @Nested
    class AddToCart {

        @Test
        void withoutCart_createsCartAndLine() {
            when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "25000", true)));
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.empty());
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            orderService.addToCart(alice, 10L, 2);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            // Lần 1: tạo giỏ rỗng; lần 2: giỏ đã có dòng sản phẩm
            verify(orderRepository, times(2)).save(saved.capture());
            Order cart = saved.getValue();
            assertThat(cart.getUser()).isSameAs(alice);
            assertThat(cart.getStatus()).isEqualTo(OrderStatus.CART);
            assertThat(cart.getOrderDetails()).singleElement().satisfies(detail -> {
                assertThat(detail.getQuantity()).isEqualTo(2);
                assertThat(detail.getUnitPrice()).isEqualByComparingTo("25000");
                assertThat(detail.getSubtotal()).isEqualByComparingTo("50000");
            });
            assertThat(cart.getTotalPrice()).isEqualByComparingTo("50000");
        }

        @Test
        void sameProductAgain_increasesQuantityOfExistingLine() {
            Product coffee = product(10L, "25000", true);
            Order cart = cartOf(alice, OrderStatus.CART);
            line(500L, cart, coffee, 1);
            when(productRepository.findById(10L)).thenReturn(Optional.of(coffee));
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cart));

            orderService.addToCart(alice, 10L, 3);

            assertThat(cart.getOrderDetails()).singleElement().satisfies(detail -> {
                assertThat(detail.getQuantity()).isEqualTo(4);
                assertThat(detail.getSubtotal()).isEqualByComparingTo("100000");
            });
            assertThat(cart.getTotalPrice()).isEqualByComparingTo("100000");
            verify(orderRepository).save(cart);
        }

        @Test
        void nonPositiveQuantity_addsOne() {
            Order cart = cartOf(alice, OrderStatus.CART);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "25000", true)));
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cart));

            orderService.addToCart(alice, 10L, -5);

            assertThat(cart.getOrderDetails()).singleElement()
                    .satisfies(detail -> assertThat(detail.getQuantity()).isEqualTo(1));
        }

        @Test
        void unavailableProduct_isRejected() {
            when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "25000", false)));

            assertThatIllegalArgumentException().isThrownBy(() -> orderService.addToCart(alice, 10L, 1));
            verify(orderRepository, never()).save(any());
        }

        @Test
        void unknownProduct_throwsNotFound() {
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.addToCart(alice, 99L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(orderRepository, never()).save(any());
        }
    }

    @Nested
    class UpdateAndRemoveCartItem {

        @Test
        void updateOwnItem_recalculatesSubtotalAndTotal() {
            Order cart = cartOf(alice, OrderStatus.CART);
            OrderDetail coffee = line(500L, cart, product(10L, "25000", true), 1);
            line(501L, cart, product(11L, "10000", true), 1);
            when(orderDetailRepository.findById(500L)).thenReturn(Optional.of(coffee));

            orderService.updateCartItemQuantity(alice, 500L, 3);

            assertThat(coffee.getQuantity()).isEqualTo(3);
            assertThat(coffee.getSubtotal()).isEqualByComparingTo("75000");
            assertThat(cart.getTotalPrice()).isEqualByComparingTo("85000");
            verify(orderRepository).save(cart);
        }

        @Test
        void updateOwnItemToZero_removesLine() {
            Order cart = cartOf(alice, OrderStatus.CART);
            OrderDetail coffee = line(500L, cart, product(10L, "25000", true), 2);
            when(orderDetailRepository.findById(500L)).thenReturn(Optional.of(coffee));

            orderService.updateCartItemQuantity(alice, 500L, 0);

            assertThat(cart.getOrderDetails()).isEmpty();
            assertThat(cart.getTotalPrice()).isEqualByComparingTo("0");
            verify(orderRepository).save(cart);
        }

        @Test
        void updateItemOfAnotherUser_isRejected() {
            Order aliceCart = cartOf(alice, OrderStatus.CART);
            OrderDetail coffee = line(500L, aliceCart, product(10L, "25000", true), 1);
            when(orderDetailRepository.findById(500L)).thenReturn(Optional.of(coffee));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> orderService.updateCartItemQuantity(mallory, 500L, 50));
            assertThat(coffee.getQuantity()).isEqualTo(1);
            verify(orderRepository, never()).save(any());
        }

        @Test
        void removeOwnItem_removesLineAndRecalculatesTotal() {
            Order cart = cartOf(alice, OrderStatus.CART);
            OrderDetail coffee = line(500L, cart, product(10L, "25000", true), 1);
            line(501L, cart, product(11L, "10000", true), 2);
            when(orderDetailRepository.findById(500L)).thenReturn(Optional.of(coffee));

            orderService.removeCartItem(alice, 500L);

            assertThat(cart.getOrderDetails()).extracting(OrderDetail::getId).containsExactly(501L);
            assertThat(cart.getTotalPrice()).isEqualByComparingTo("20000");
            verify(orderRepository).save(cart);
        }

        @Test
        void removeItemOfAnotherUser_isRejected() {
            Order aliceCart = cartOf(alice, OrderStatus.CART);
            OrderDetail coffee = line(500L, aliceCart, product(10L, "25000", true), 1);
            when(orderDetailRepository.findById(500L)).thenReturn(Optional.of(coffee));

            assertThatIllegalArgumentException().isThrownBy(() -> orderService.removeCartItem(mallory, 500L));
            assertThat(aliceCart.getOrderDetails()).containsExactly(coffee);
            verify(orderRepository, never()).save(any());
        }

        @Test
        void removeItemOfPlacedOrder_isRejected() {
            Order placed = cartOf(alice, OrderStatus.PENDING);
            OrderDetail coffee = line(500L, placed, product(10L, "25000", true), 1);
            when(orderDetailRepository.findById(500L)).thenReturn(Optional.of(coffee));

            assertThatIllegalArgumentException().isThrownBy(() -> orderService.removeCartItem(alice, 500L));
            verify(orderRepository, never()).save(any());
        }

        @Test
        void unknownItem_throwsNotFound() {
            when(orderDetailRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.removeCartItem(alice, 404L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Checkout {

        @Test
        void withoutCart_isRejected() {
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.empty());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> orderService.checkout(alice, "1 Lê Lợi", null))
                    .withMessageContaining("trống");
            verify(orderRepository, never()).save(any());
            verifyNoInteractions(outboxService);
        }

        @Test
        void emptyCart_isRejected() {
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cartOf(alice, OrderStatus.CART)));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> orderService.checkout(alice, "1 Lê Lợi", null))
                    .withMessageContaining("trống");
            verifyNoInteractions(outboxService);
        }

        @Test
        void blankShippingAddress_isRejected() {
            Order cart = cartOf(alice, OrderStatus.CART);
            line(500L, cart, product(10L, "25000", true), 1);
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cart));

            assertThatIllegalArgumentException().isThrownBy(() -> orderService.checkout(alice, "   ", null));
            assertThat(cart.getStatus()).isEqualTo(OrderStatus.CART);
            verifyNoInteractions(outboxService);
        }

        @Test
        void placesOrder_andEnqueuesMessageSnapshotInOutbox() {
            Order cart = cartOf(alice, OrderStatus.CART);
            line(500L, cart, product(10L, "25000", true), 2);
            when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cart));

            orderService.checkout(alice, "  1 Lê Lợi  ", "Ít đá");

            assertThat(cart.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(cart.getOrderedAt()).isNotNull();
            assertThat(cart.getShippingAddress()).isEqualTo("1 Lê Lợi");
            verify(orderRepository).save(cart);

            ArgumentCaptor<OrderPlacedMessage> event = ArgumentCaptor.forClass(OrderPlacedMessage.class);
            verify(outboxService).enqueue(eq(OutboxType.ORDER_PLACED), event.capture());
            assertThat(event.getValue().orderId()).isEqualTo(100L);
            assertThat(event.getValue().customerEmail()).isEqualTo("alice@example.com");
            assertThat(event.getValue().totalPrice()).isEqualByComparingTo("50000");
            assertThat(event.getValue().items()).singleElement()
                    .satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
        }
    }

    @Test
    void countPendingOrders_countsOnlyPendingStatus() {
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenReturn(5L);

        assertThat(orderService.countPendingOrders()).isEqualTo(5L);
    }

    @Nested
    class UpdateStatus {

        @Test
        void cartToPending_setsOrderedAt_andWritesAudit() {
            Order order = cartOf(alice, OrderStatus.CART);
            when(orderRepository.findByIdWithUser(100L)).thenReturn(Optional.of(order));

            orderService.updateStatus(100L, OrderStatus.PENDING);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getOrderedAt()).isNotNull();
            verify(orderRepository).save(order);
            verify(auditLogService).orderStatusChanged(100L, OrderStatus.CART, OrderStatus.PENDING);
            // Giỏ hàng chưa phải đơn của khách: không phát sự kiện đổi trạng thái
            verifyNoInteractions(outboxService);
        }

        @Test
        void statusChange_enqueuesEventForOwner_inSameTransaction() {
            when(orderRepository.findByIdWithUser(100L)).thenReturn(Optional.of(cartOf(alice, OrderStatus.PENDING)));

            orderService.updateStatus(100L, OrderStatus.COMPLETED);

            // Chủ đơn lấy từ DB (không từ request); outbox ghi trong transaction của updateStatus → chỉ gửi sau commit
            verify(outboxService).enqueue(OutboxType.ORDER_STATUS_CHANGED,
                    new OrderStatusChangedMessage(100L, "alice", OrderStatus.COMPLETED));
        }

        @Test
        void movingOrderBackToCart_doesNotNotify() {
            when(orderRepository.findByIdWithUser(100L)).thenReturn(Optional.of(cartOf(alice, OrderStatus.PENDING)));

            orderService.updateStatus(100L, OrderStatus.CART);

            verifyNoInteractions(outboxService);
        }

        @Test
        void laterTransition_keepsOriginalOrderedAt() {
            LocalDateTime orderedAt = LocalDateTime.of(2026, 1, 2, 3, 4);
            Order order = cartOf(alice, OrderStatus.PENDING);
            order.setOrderedAt(orderedAt);
            when(orderRepository.findByIdWithUser(100L)).thenReturn(Optional.of(order));

            orderService.updateStatus(100L, OrderStatus.PROCESSING);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(order.getOrderedAt()).isEqualTo(orderedAt);
            verify(auditLogService).orderStatusChanged(100L, OrderStatus.PENDING, OrderStatus.PROCESSING);
        }

        @Test
        void sameStatus_isNoOp() {
            when(orderRepository.findByIdWithUser(100L)).thenReturn(Optional.of(cartOf(alice, OrderStatus.COMPLETED)));

            orderService.updateStatus(100L, OrderStatus.COMPLETED);

            verify(orderRepository, never()).save(any());
            verifyNoInteractions(auditLogService, outboxService);
        }

        @Test
        void unknownOrder_throwsNotFound() {
            when(orderRepository.findByIdWithUser(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.updateStatus(404L, OrderStatus.CANCELLED))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(auditLogService, outboxService);
        }
    }

    @Nested
    class OrderStatusesForUser {

        @Test
        void returnsStatusesOfOwnOrders_withLabelAndBadge_queryScopedToUser() {
            OrderStatusView own = mock(OrderStatusView.class);
            when(own.getId()).thenReturn(7L);
            when(own.getStatus()).thenReturn(OrderStatus.PROCESSING);
            // Đơn 8 của người khác: repository lọc theo userId nên không trả về
            when(orderRepository.findStatusesByUserIdAndIdIn(1L, Set.of(7L, 8L))).thenReturn(List.of(own));

            assertThat(orderService.getOrderStatusesForUser(1L, List.of(7L, 8L, 7L)))
                    .containsExactly(new OrderStatusEventResDto(7L, OrderStatus.PROCESSING,
                            OrderStatus.PROCESSING.getLabel(), OrderStatus.PROCESSING.getBadgeClass()));
        }

        @Test
        void emptyOrTooManyIds_areRejected() {
            List<Long> tooMany = LongStream.rangeClosed(1, OrderService.MAX_STATUS_IDS + 1).boxed().toList();

            assertThatThrownBy(() -> orderService.getOrderStatusesForUser(1L, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> orderService.getOrderStatusesForUser(1L, tooMany))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(orderRepository);
        }
    }

    @Test
    void cartItemCount_delegatesToRepository() {
        when(orderDetailRepository.sumCartQuantityByUserId(1L)).thenReturn(7);

        assertThat(orderService.getCartItemCount(1L)).isEqualTo(7);
    }

    @Test
    void emptyCart_isReturnedAsNull() {
        when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cartOf(alice, OrderStatus.CART)));

        assertThat(orderService.getCartForUser(1L)).isNull();
        verifyNoInteractions(productImageRepository);
    }

    @Test
    void orderOfAnotherUser_isNotFound() {
        when(orderRepository.findByIdAndUserIdWithDetailsAndProducts(100L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderDetailForUser(100L, 2L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cartWithItems_loadsPrimaryImagesInOneQuery() {
        Order cart = cartOf(alice, OrderStatus.CART);
        line(500L, cart, product(10L, "25000", true), 1);
        line(501L, cart, product(11L, "10000", true), 1);
        when(orderRepository.findCartByUserId(1L)).thenReturn(Optional.of(cart));
        when(productImageRepository.findPrimaryUrlsByProductIds(List.of(10L, 11L)))
                .thenReturn(List.<Object[]>of(new Object[]{10L, "/uploads/products/a.png"}));

        assertThat(orderService.getCartForUser(1L)).isNotNull();
        verify(productImageRepository).findPrimaryUrlsByProductIds(List.of(10L, 11L));
    }
}
