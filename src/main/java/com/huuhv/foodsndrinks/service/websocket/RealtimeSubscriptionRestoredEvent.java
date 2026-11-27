package com.huuhv.foodsndrinks.service.websocket;

/**
 * Sự kiện Spring (đồng bộ, trong instance): subscription JMS non-durable của một listener realtime vừa được tạo LẠI sau
 * khi mất kết nối broker ({@code JmsConfig.TOPIC_BROADCAST_FACTORY}). Trong lúc subscription chưa có, broker bỏ mọi
 * message gửi tới topic cho instance này → client WebSocket đang nối vào instance có thể đã lỡ sự kiện mà không biết
 * (socket của trình duyệt không bị ngắt khi kết nối JMS ngắt). {@link WebSocketSessionTracker} đóng các socket mở trước
 * thời điểm này để client nối lại và tải lại số liệu (resync).
 *
 * @param destination topic của subscription (chỉ để ghi log)
 */
public record RealtimeSubscriptionRestoredEvent(String destination) {
}
