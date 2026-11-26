package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Nội dung chữ thuần của thông báo: phần {@code text/plain} của email (bản HTML render từ {@code templates/mail/*}).
 * Slack dùng Block Kit riêng ({@link SlackMessageBuilder}).
 */
final class OrderMessageFormatter {

    private OrderMessageFormatter() {
    }

    /** Số tiền kiểu Việt Nam ({@code 150.000}) — cùng quy tắc với email HTML/Slack ({@link NotificationFormat}). */
    static String formatMoney(BigDecimal amount) {
        return NotificationFormat.INSTANCE.money(amount);
    }

    static String formatDateTime(LocalDateTime dateTime) {
        return NotificationFormat.INSTANCE.dateTime(dateTime);
    }

    static String buildOrderSummary(OrderPlacedEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Đơn hàng mới #").append(event.orderId()).append('\n');
        if (event.orderedAt() != null) {
            sb.append("Thời gian: ").append(formatDateTime(event.orderedAt())).append('\n');
        }
        sb.append("Khách hàng: ").append(event.customerName())
                .append(" (").append(event.customerEmail()).append(')');
        if (event.customerPhone() != null && !event.customerPhone().isBlank()) {
            sb.append(" - SĐT: ").append(event.customerPhone());
        }
        sb.append('\n');
        sb.append("Địa chỉ giao hàng: ").append(event.shippingAddress()).append('\n');
        if (event.note() != null && !event.note().isBlank()) {
            sb.append("Ghi chú: ").append(event.note()).append('\n');
        }
        sb.append("Chi tiết đơn hàng:\n");
        for (OrderPlacedEvent.OrderLineItem item : event.items()) {
            sb.append("  - ").append(item.productName())
                    .append(" x").append(item.quantity())
                    .append(" = ").append(formatMoney(item.subtotal())).append(" VND\n");
        }
        sb.append("Tổng tiền: ").append(formatMoney(event.totalPrice())).append(" VND");
        return sb.toString();
    }

    static String buildMonthlyStatistics(int year, int month, BigDecimal revenue, long completedCount,
                                         Map<OrderStatus, Long> statusCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thống kê đơn hàng tháng %02d/%d\n\n".formatted(month, year));
        sb.append("Đơn hàng hoàn thành: ").append(completedCount).append('\n');
        sb.append("Doanh thu: ").append(formatMoney(revenue)).append(" VND\n\n");
        sb.append("Chi tiết theo trạng thái:\n");
        statusCounts.forEach((status, count) ->
                sb.append("  - ").append(status.getLabel()).append(": ").append(count).append('\n'));
        return sb.toString();
    }
}
