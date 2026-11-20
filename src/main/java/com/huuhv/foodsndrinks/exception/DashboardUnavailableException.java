package com.huuhv.foodsndrinks.exception;

/**
 * Không tổng hợp được số liệu dashboard: một truy vấn thống kê lỗi hoặc cả lượt tải quá {@code app.async.dashboard.timeout}.
 * {@code AdminExceptionHandler} trả 503 với thông báo chung — message/cause chỉ để ghi log.
 */
public class DashboardUnavailableException extends RuntimeException {

    public DashboardUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
