package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.AdminOrderEventResDto;
import com.huuhv.foodsndrinks.enums.AdminOrderEventType;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Đẩy sự kiện đơn hàng tới admin đang mở trang quản trị ({@value StompDestinations#ADMIN_ORDERS}) trên instance này.
 * Được gọi trên MỌI instance (consumer JMS non-durable), mỗi instance chỉ phát cho client nối vào nó.
 * <p>
 * Đếm đơn chờ bằng {@link OrderRepository} (cùng truy vấn với {@code OrderService.countPendingOrders}) thay vì gọi
 * {@code OrderService}: {@code service} đã phụ thuộc {@code service.messaging} (outbox), consumer ở
 * {@code service.messaging} gọi lớp này — gọi ngược {@code OrderService} sẽ tạo vòng phụ thuộc giữa package.
 * <p>
 * <b>Đếm + gửi nằm trong cùng một khoá</b>: hai listener (đơn mới, đổi trạng thái) chạy trên hai thread; không khoá thì
 * số đếm sớm hơn có thể được gửi SAU số đếm muộn hơn → badge của admin lùi về giá trị cũ. Có khoá, thứ tự gửi = thứ tự
 * đếm, và kênh outbound giữ thứ tự theo session ({@code preservePublishOrder}) nên giá trị cuối admin nhận là mới nhất.
 * Mỗi lần đếm là một truy vấn riêng trong khoá (không {@code @Transactional} bao quanh method: transaction mở trước khi
 * vào khoá có thể giữ snapshot REPEATABLE READ cũ).
 * <p>
 * Trước khi đếm, phát {@link OrdersChangedEvent} (đồng bộ): cache dẫn xuất từ đơn hàng của instance
 * ({@code DashboardChartCache}) bị xoá TRƯỚC khi client nhận sự kiện và tải lại — và vẫn bị xoá khi truy vấn đếm lỗi
 * (dữ liệu đơn đã đổi dù badge không gửi được).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderNotifier {

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ReentrantLock sendLock = new ReentrantLock();

    public void orderPlaced(OrderPlacedMessage message) {
        int itemCount = message.items().stream().mapToInt(OrderPlacedMessage.OrderLineItem::quantity).sum();
        countAndSend(message.orderId(), pendingCount -> new AdminOrderEventResDto(AdminOrderEventType.ORDER_PLACED,
                message.orderId(), itemCount, message.totalPrice(), pendingCount));
    }

    /**
     * Một admin đổi trạng thái đơn → mọi admin đang online cập nhật badge "chờ xác nhận" (đếm lại từ DB) và dashboard tải
     * lại biểu đồ (doanh thu/top sản phẩm chỉ tính đơn hoàn thành nên chỉ đổi qua sự kiện này).
     */
    public void orderStatusChanged(OrderStatusChangedMessage message) {
        countAndSend(message.orderId(), pendingCount -> new AdminOrderEventResDto(
                AdminOrderEventType.ORDER_STATUS_CHANGED, message.orderId(), null, null, pendingCount));
    }

    private void countAndSend(Long orderId, Function<Long, AdminOrderEventResDto> eventFactory) {
        // Ngoài khoá: xoá cache không phụ thuộc thứ tự gửi badge, và không phụ thuộc truy vấn đếm thành công
        eventPublisher.publishEvent(new OrdersChangedEvent(orderId));
        sendLock.lock();
        try {
            AdminOrderEventResDto event = eventFactory.apply(orderRepository.countByStatus(OrderStatus.PENDING));
            messagingTemplate.convertAndSend(StompDestinations.ADMIN_ORDERS, event);
            log.debug("Sent {} for order #{} to {}", event.type(), event.orderId(), StompDestinations.ADMIN_ORDERS);
        } finally {
            sendLock.unlock();
        }
    }
}
