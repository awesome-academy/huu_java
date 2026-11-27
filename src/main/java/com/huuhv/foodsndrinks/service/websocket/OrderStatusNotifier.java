package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Đẩy trạng thái của đơn tới chủ đơn ({@value StompDestinations#USER_ORDERS}) trên instance này.
 * <p>
 * <b>Trạng thái gửi đi đọc lại từ DB lúc gửi</b>, không lấy từ message: mỗi lần đổi trạng thái là một bản ghi outbox gửi
 * độc lập (song song trên pool {@code outbox-*}, hoặc gửi lại sau bởi job) nên message có thể tới SAI THỨ TỰ — dùng
 * {@code status} trong message thì trang của khách có thể lùi về trạng thái cũ. Message chỉ là tín hiệu "đơn này vừa
 * đổi"; listener xử lý tuần tự (concurrency 1) và kênh outbound giữ thứ tự theo session ({@code preservePublishOrder}),
 * nên message cuối cùng khách nhận luôn mang trạng thái mới nhất.
 * <p>
 * Chống IDOR: chỉ gửi khi đơn thuộc đúng {@code username} trong message (query lọc theo chủ đơn), và
 * {@code convertAndSendToUser} chỉ tới các phiên có {@code Principal#getName()} bằng tên đó. Instance không có phiên nào
 * của user ({@link SimpUserRegistry} cục bộ của simple broker) → bỏ qua, không tốn query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusNotifier {

    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;
    private final OrderRepository orderRepository;

    public void notifyOwner(OrderStatusChangedMessage message) {
        if (userRegistry.getUser(message.username()) == null) {
            return;
        }
        Optional<OrderStatus> current = orderRepository.findStatusByIdAndOwnerUsername(message.orderId(),
                message.username());
        if (current.isEmpty() || current.get() == OrderStatus.CART) {
            log.debug("Order #{} not found for its owner or is a cart, skipping status push", message.orderId());
            return;
        }
        messagingTemplate.convertAndSendToUser(message.username(), StompDestinations.USER_ORDERS_QUEUE,
                OrderStatusEventResDto.of(message.orderId(), current.get()));
        log.debug("Sent status {} of order #{} to its owner", current.get(), message.orderId());
    }
}
