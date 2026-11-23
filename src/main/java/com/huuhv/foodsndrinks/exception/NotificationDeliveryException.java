package com.huuhv.foodsndrinks.exception;

/**
 * Gửi một thông báo (Slack, email) thất bại. Consumer JMS để exception này thoát ra để broker giao lại message (retry/DLQ
 * cấu hình trên broker). Thông điệp không chứa secret (vd. URL webhook Slack).
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
