package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.OrderStatus;

/** Id + trạng thái của đơn (không load cả entity {@code Order}) — trang của chủ đơn tải lại badge sau khi kết nối lại. */
public interface OrderStatusView {

    Long getId();

    OrderStatus getStatus();
}
