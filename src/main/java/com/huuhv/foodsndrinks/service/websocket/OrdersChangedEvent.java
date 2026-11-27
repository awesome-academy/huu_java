package com.huuhv.foodsndrinks.service.websocket;

/**
 * Sự kiện Spring (đồng bộ, trong instance) phát bởi {@link AdminOrderNotifier} TRƯỚC khi đẩy sự kiện đơn hàng tới admin:
 * dữ liệu đơn đã đổi (đã commit) — cache dẫn xuất từ đơn hàng của instance này ({@code DashboardChartCache}) phải bỏ,
 * để lần tải lại mà sự kiện STOMP kích hoạt đọc số liệu mới.
 *
 * @param orderId đơn vừa được đặt/đổi trạng thái
 */
public record OrdersChangedEvent(Long orderId) {
}
