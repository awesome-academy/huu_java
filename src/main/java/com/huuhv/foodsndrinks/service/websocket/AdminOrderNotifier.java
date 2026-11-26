package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.response.AdminOrderEventResDto;
import com.huuhv.foodsndrinks.enums.AdminOrderEventType;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đẩy sự kiện đơn hàng tới admin đang mở trang quản trị ({@value StompDestinations#ADMIN_ORDERS}) trên instance này.
 * Được gọi trên MỌI instance (consumer JMS non-durable), mỗi instance chỉ phát cho client nối vào nó.
 * <p>
 * Đếm đơn chờ bằng {@link OrderRepository} (cùng truy vấn với {@code OrderService.countPendingOrders}) thay vì gọi
 * {@code OrderService}: {@code service} đã phụ thuộc {@code service.messaging} (outbox), consumer ở
 * {@code service.messaging} gọi lớp này — gọi ngược {@code OrderService} sẽ tạo vòng phụ thuộc giữa package.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderNotifier {

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public void orderPlaced(OrderPlacedMessage message) {
        int itemCount = message.items().stream().mapToInt(OrderPlacedMessage.OrderLineItem::quantity).sum();
        AdminOrderEventResDto event = new AdminOrderEventResDto(AdminOrderEventType.ORDER_PLACED, message.orderId(),
                itemCount, message.totalPrice(), orderRepository.countByStatus(OrderStatus.PENDING));
        messagingTemplate.convertAndSend(StompDestinations.ADMIN_ORDERS, event);
        log.debug("Sent {} for order #{} to {}", event.type(), event.orderId(), StompDestinations.ADMIN_ORDERS);
    }
}
