package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.response.AdminOrderEventResDto;
import com.huuhv.foodsndrinks.enums.AdminOrderEventType;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Đơn mới → một sự kiện trên kênh admin, kèm số đơn chờ đọc từ DB, không mang dữ liệu cá nhân của khách. */
@ExtendWith(MockitoExtension.class)
class AdminOrderNotifierTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private OrderRepository orderRepository;
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
}
