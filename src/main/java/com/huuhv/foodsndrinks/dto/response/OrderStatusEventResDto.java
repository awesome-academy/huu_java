package com.huuhv.foodsndrinks.dto.response;

import com.huuhv.foodsndrinks.enums.OrderStatus;

/**
 * Trạng thái hiện tại của một đơn cho trang của chủ đơn ({@code static/js/web/order-status.js}): payload STOMP trên
 * {@code /user/queue/orders} khi admin đổi trạng thái, và phần tử của {@code GET /orders/statuses} (tải lại sau khi kết
 * nối lại). Nhãn/class badge lấy từ {@link OrderStatus} — một nguồn với lúc server render trang. Chỉ thêm field.
 *
 * @param orderId     đơn
 * @param status      tên hằng {@link OrderStatus}
 * @param statusLabel nhãn hiển thị, vd. "Đang xử lý"
 * @param badgeClass  class Bootstrap của badge, vd. "bg-info text-dark"
 */
public record OrderStatusEventResDto(Long orderId, OrderStatus status, String statusLabel, String badgeClass) {

    public static OrderStatusEventResDto of(Long orderId, OrderStatus status) {
        return new OrderStatusEventResDto(orderId, status, status.getLabel(), status.getBadgeClass());
    }
}
