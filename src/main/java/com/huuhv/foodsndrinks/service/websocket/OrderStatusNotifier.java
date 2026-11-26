package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Đẩy trạng thái mới của đơn tới chủ đơn ({@value StompDestinations#USER_ORDERS}) trên instance này.
 * <p>
 * Gửi theo {@code username} trong message (chủ đơn, lấy từ DB lúc đổi trạng thái): {@code UserDestinationMessageHandler}
 * chỉ chuyển tới các phiên WebSocket có {@code Principal#getName()} đúng bằng tên đó — user khác không bao giờ nhận
 * (chống IDOR ở tầng đích, không dựa vào client lọc). Gọi trên MỌI instance (consumer JMS non-durable): instance không có
 * phiên nào của user đó thì Spring bỏ message, không lỗi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    public void notifyOwner(OrderStatusChangedMessage message) {
        messagingTemplate.convertAndSendToUser(message.username(), StompDestinations.USER_ORDERS_QUEUE,
                OrderStatusEventResDto.of(message.orderId(), message.status()));
        log.debug("Sent status {} of order #{} to its owner", message.status(), message.orderId());
    }
}
