-- Thông báo đơn hàng đã gửi, mỗi (đơn, kênh) một dòng — chống gửi lặp khi broker JMS giao lại message
-- (service/messaging/NotificationLogService). Consumer chèn dòng TRƯỚC khi gửi, trong cùng transaction với lần gửi:
-- gửi lỗi → rollback, dòng biến mất và message được giao lại; message trùng → khoá unique chặn, không gửi lần hai.
-- Không có FK tới orders: message tự chứa nội dung thông báo, bảng chỉ là nhật ký giao (giống audit), không khoá dòng
-- của đơn hàng trong lúc gọi Slack/SMTP.
CREATE TABLE notification_logs (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    order_id BIGINT       NOT NULL,
    channel  VARCHAR(16)  NOT NULL,                         -- NotificationChannel: EMAIL, SLACK
    sent_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_logs_order_channel UNIQUE (order_id, channel)
);
