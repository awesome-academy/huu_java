package com.huuhv.foodsndrinks.enums;

/** Trạng thái bản ghi {@code outbox_messages}. Không đổi tên giá trị đã lưu (SQL của repository dùng chuỗi này). */
public enum OutboxStatus {
    /** Chưa gửi được lên broker — publisher/job còn thử lại. */
    PENDING,
    /** Broker đã nhận. */
    SENT,
    /** Hết số lần thử ({@code app.outbox.max-attempts}) — cần xử lý tay (README, mục Messaging). */
    FAILED
}
