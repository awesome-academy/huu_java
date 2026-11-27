package com.huuhv.foodsndrinks.enums;

/**
 * Loại sự kiện đơn hàng gửi tới admin qua STOMP ({@code /topic/admin/orders}). Tên hằng số là hợp đồng JSON với
 * {@code static/js/admin/realtime.js}: chỉ thêm giá trị mới (JS bỏ qua loại không biết), không đổi tên.
 */
public enum AdminOrderEventType {
    /** Có đơn mới (checkout). */
    ORDER_PLACED,
    /** Một admin vừa đổi trạng thái đơn (badge đơn chờ + biểu đồ dashboard cần làm mới). */
    ORDER_STATUS_CHANGED
}
