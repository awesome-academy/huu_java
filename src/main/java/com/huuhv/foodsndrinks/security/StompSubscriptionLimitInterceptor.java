package com.huuhv.foodsndrinks.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Giới hạn SUBSCRIBE trên mỗi kết nối STOMP (kênh inbound, chạy SAU các interceptor phân quyền của
 * {@code @EnableWebSocketSecurity} — frame bị từ chối không được tính).
 * <p>
 * Simple broker thêm một bản ghi vào registry cho MỖI frame SUBSCRIBE có {@code id} mới, kể cả cùng đích, và mỗi sự kiện
 * sau đó gửi một bản cho từng bản ghi: một user đã đăng nhập gửi hàng triệu SUBSCRIBE {@code /user/queue/orders} sẽ làm
 * phình heap và nhân số message (DoS). Ở đây:
 * <ul>
 *   <li>SUBSCRIBE trùng đích trên cùng kết nối → từ chối (trang thật không bao giờ làm vậy);</li>
 *   <li>quá {@code maxSubscriptions} subscription đang mở → từ chối;</li>
 *   <li>UNSUBSCRIBE trả lại chỗ.</li>
 * </ul>
 * Từ chối = ném {@link MessageDeliveryException}: Spring gửi frame ERROR (thông điệp cố định của {@link StompErrorHandler})
 * rồi đóng kết nối. Trạng thái nằm trong session attributes của WebSocket — mất theo kết nối.
 */
public class StompSubscriptionLimitInterceptor implements ChannelInterceptor {

    static final String SUBSCRIPTIONS_ATTRIBUTE = StompSubscriptionLimitInterceptor.class.getName() + ".subscriptions";

    private final int maxSubscriptions;

    public StompSubscriptionLimitInterceptor(int maxSubscriptions) {
        if (maxSubscriptions < 1) {
            throw new IllegalArgumentException("maxSubscriptions must be >= 1");
        }
        this.maxSubscriptions = maxSubscriptions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaders headers = message.getHeaders();
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(headers);
        if (type == SimpMessageType.SUBSCRIBE) {
            subscribe(message, headers);
        } else if (type == SimpMessageType.UNSUBSCRIBE) {
            Map<String, String> subscriptions = subscriptions(headers);
            String id = SimpMessageHeaderAccessor.getSubscriptionId(headers);
            if (subscriptions != null && id != null) {
                synchronized (subscriptions) {
                    subscriptions.remove(id);
                }
            }
        }
        return message;
    }

    private void subscribe(Message<?> message, MessageHeaders headers) {
        Map<String, String> subscriptions = subscriptions(headers);
        String id = SimpMessageHeaderAccessor.getSubscriptionId(headers);
        String destination = SimpMessageHeaderAccessor.getDestination(headers);
        if (subscriptions == null || id == null || destination == null) {
            throw new MessageDeliveryException(message, "Invalid SUBSCRIBE");
        }
        synchronized (subscriptions) {
            if (subscriptions.containsKey(id) || subscriptions.containsValue(destination)) {
                throw new MessageDeliveryException(message, "Duplicate subscription");
            }
            if (subscriptions.size() >= maxSubscriptions) {
                throw new MessageDeliveryException(message, "Too many subscriptions");
            }
            subscriptions.put(id, destination);
        }
    }

    /** subscription id → đích, của kết nối gửi frame; {@code null} nếu frame không gắn với kết nối WebSocket. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> subscriptions(MessageHeaders headers) {
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(headers);
        if (attributes == null) {
            return null;
        }
        return (Map<String, String>) attributes.computeIfAbsent(SUBSCRIPTIONS_ATTRIBUTE, key -> new HashMap<>());
    }
}
