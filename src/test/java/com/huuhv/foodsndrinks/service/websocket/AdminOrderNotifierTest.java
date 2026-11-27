package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.AdminOrderEventResDto;
import com.huuhv.foodsndrinks.enums.AdminOrderEventType;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Đơn mới → một sự kiện trên kênh admin, kèm số đơn chờ đọc từ DB, không mang dữ liệu cá nhân của khách. */
@ExtendWith(MockitoExtension.class)
class AdminOrderNotifierTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private OrderRepository orderRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private AdminOrderNotifier notifier;

    @Test
    void orderPlaced_sendsSummaryWithPendingCount() {
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenReturn(7L);
        OrderPlacedMessage message = new OrderPlacedMessage(42L, "Nguyễn Văn A", "a@example.com", "0901234567",
                "12 Lê Lợi", null, new BigDecimal("120000.00"), LocalDateTime.of(2026, 11, 26, 9, 0), List.of(
                new OrderPlacedMessage.OrderLineItem("Trà sữa", 2, new BigDecimal("35000"), new BigDecimal("70000")),
                new OrderPlacedMessage.OrderLineItem("Bánh mì", 1, new BigDecimal("50000"), new BigDecimal("50000"))));

        notifier.orderPlaced(message);

        verify(messagingTemplate).convertAndSend(StompDestinations.ADMIN_ORDERS, new AdminOrderEventResDto(
                AdminOrderEventType.ORDER_PLACED, 42L, 3, new BigDecimal("120000.00"), 7L));
    }

    @Test
    void orderStatusChanged_sendsRecountedPendingCount_withoutOwnerName() {
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenReturn(2L);

        notifier.orderStatusChanged(new OrderStatusChangedMessage(42L, "alice", OrderStatus.COMPLETED));

        verify(messagingTemplate).convertAndSend(StompDestinations.ADMIN_ORDERS, new AdminOrderEventResDto(
                AdminOrderEventType.ORDER_STATUS_CHANGED, 42L, null, null, 2L));
    }

    @Test
    void cachesAreClearedBeforeAdminsAreNotified() {
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenReturn(2L);

        notifier.orderStatusChanged(new OrderStatusChangedMessage(42L, "alice", OrderStatus.COMPLETED));

        // DashboardChartCache xoá dữ liệu TRƯỚC khi client nhận sự kiện và tải lại biểu đồ
        InOrder order = inOrder(eventPublisher, messagingTemplate);
        order.verify(eventPublisher).publishEvent(new OrdersChangedEvent(42L));
        order.verify(messagingTemplate).convertAndSend(eq(StompDestinations.ADMIN_ORDERS), any(Object.class));
    }

    @Test
    void countFailure_stillClearsCaches_butSendsNothing() {
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> notifier.orderStatusChanged(
                new OrderStatusChangedMessage(42L, "alice", OrderStatus.COMPLETED)))
                .isInstanceOf(IllegalStateException.class);

        // Dữ liệu đơn đã đổi dù badge không đếm được: biểu đồ không được giữ số liệu cũ tới hết TTL
        verify(eventPublisher).publishEvent(new OrdersChangedEvent(42L));
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void concurrentEvents_areSentInTheOrderTheyWereCounted() throws Exception {
        // Listener "đơn mới" và "đổi trạng thái" chạy trên 2 thread: số đếm sớm (3) không được gửi sau số đếm muộn (4)
        CountDownLatch firstCounting = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger counts = new AtomicInteger();
        when(orderRepository.countByStatus(OrderStatus.PENDING)).thenAnswer(invocation -> {
            if (counts.incrementAndGet() == 1) {
                firstCounting.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
                return 3L;
            }
            return 4L;
        });
        OrderStatusChangedMessage message = new OrderStatusChangedMessage(42L, "alice", OrderStatus.COMPLETED);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> notifier.orderStatusChanged(message));
            assertThat(firstCounting.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = threads.submit(() -> notifier.orderStatusChanged(message));

            // Thread 2 phải chờ khoá: chưa được đếm khi thread 1 còn giữ khoá
            Thread.sleep(200);
            assertThat(counts.get()).isEqualTo(1);
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        InOrder order = inOrder(messagingTemplate);
        order.verify(messagingTemplate).convertAndSend(StompDestinations.ADMIN_ORDERS, new AdminOrderEventResDto(
                AdminOrderEventType.ORDER_STATUS_CHANGED, 42L, null, null, 3L));
        order.verify(messagingTemplate).convertAndSend(StompDestinations.ADMIN_ORDERS, new AdminOrderEventResDto(
                AdminOrderEventType.ORDER_STATUS_CHANGED, 42L, null, null, 4L));
    }
}
