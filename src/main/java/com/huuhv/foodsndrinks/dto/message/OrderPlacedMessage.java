package com.huuhv.foodsndrinks.dto.message;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Message JMS trên topic {@code order.placed} (alias {@code orderPlaced} trong {@code MessageTypes}): ảnh chụp phẳng của
 * đơn vừa đặt, chỉ gồm các trường cần cho thông báo (không phải entity), lấy trong transaction checkout nên consumer
 * không bao giờ chạm tới quan hệ lazy của JPA.
 * <p>
 * Message đang nằm trong subscription/DLQ được đọc lại bằng record này: chỉ <b>thêm</b> field mới (Jackson bỏ qua field
 * thiếu), không đổi tên/xoá/đổi kiểu field đã có — nếu không, message cũ không đọc được và đi thẳng vào DLQ.
 */
public record OrderPlacedMessage(
        Long orderId,
        String customerName,
        String customerEmail,
        String customerPhone,
        String shippingAddress,
        String note,
        BigDecimal totalPrice,
        LocalDateTime orderedAt,
        List<OrderLineItem> items
) {
    public record OrderLineItem(String productName, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
    }
}
