package com.huuhv.foodsndrinks.dto.response;

/** {@code GET /admin/orders/pending-count}: trang admin tải lại badge sau khi kết nối WebSocket lại (bù sự kiện lỡ). */
public record PendingOrderCountResDto(long pendingCount) {
}
