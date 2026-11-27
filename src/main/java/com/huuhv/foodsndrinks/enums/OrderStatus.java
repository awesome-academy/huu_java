package com.huuhv.foodsndrinks.enums;

import java.util.Arrays;
import java.util.List;

public enum OrderStatus {
    CART        ("Giỏ hàng",       "bg-secondary"),
    PENDING     ("Chờ xác nhận",   "bg-warning text-dark"),
    PROCESSING  ("Đang xử lý",     "bg-info text-dark"),
    COMPLETED   ("Hoàn thành",     "bg-success"),
    CANCELLED   ("Đã hủy",         "bg-danger");

    /** Trạng thái của đơn đã đặt — mọi màn hình admin (lọc, đổi trạng thái); {@code CART} là giỏ hàng, không phải đơn. */
    private static final List<OrderStatus> PLACED = Arrays.stream(values()).filter(s -> s != CART).toList();

    private final String label;
    private final String badgeClass;

    OrderStatus(String label, String badgeClass) {
        this.label      = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel()      { return label; }
    public String getBadgeClass() { return badgeClass; }

    public static List<OrderStatus> placedStatuses() { return PLACED; }
}
