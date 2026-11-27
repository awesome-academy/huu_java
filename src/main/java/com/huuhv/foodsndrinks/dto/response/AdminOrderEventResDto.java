package com.huuhv.foodsndrinks.dto.response;

import com.huuhv.foodsndrinks.enums.AdminOrderEventType;

import java.math.BigDecimal;

/**
 * Payload STOMP trên {@code /topic/admin/orders} (JSON). Tách khỏi message JMS ({@code OrderPlacedMessage}) để hai hợp
 * đồng đổi độc lập; chỉ chứa thứ toast/badge cần — không tên, email, SĐT, địa chỉ khách (admin mở trang chi tiết đơn để
 * xem). Chỉ thêm field, không đổi tên (trang admin đang mở đọc theo tên).
 *
 * @param type         loại sự kiện
 * @param orderId      đơn liên quan
 * @param itemCount    tổng số lượng món trong đơn ({@code null} nếu không áp dụng)
 * @param totalPrice   tổng tiền ({@code null} nếu không áp dụng)
 * @param pendingCount số đơn đang chờ xác nhận, tính lại từ DB lúc gửi
 */
public record AdminOrderEventResDto(AdminOrderEventType type, Long orderId, Integer itemCount, BigDecimal totalPrice,
                                    long pendingCount) {
}
