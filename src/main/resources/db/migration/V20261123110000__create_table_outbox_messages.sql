-- Transactional Outbox (service/messaging/OutboxService, OutboxPublisher): message JMS ghi trong CÙNG transaction với
-- đơn hàng, gửi lên broker sau khi commit; broker chết đúng lúc đó thì bản ghi ở PENDING và job OutboxRelayJob gửi lại.
-- Mọi mốc thời gian lấy từ đồng hồ của MySQL (CURRENT_TIMESTAMP(3)) và so sánh trong SQL — không phụ thuộc giờ của
-- từng instance. Nhiều instance chia nhau bản ghi bằng SELECT ... FOR UPDATE SKIP LOCKED.
CREATE TABLE outbox_messages (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    type            VARCHAR(64)  NOT NULL,                              -- alias kiểu payload (OutboxType / MessageTypes)
    payload         JSON         NOT NULL,                              -- body JSON gửi nguyên văn
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',            -- OutboxStatus: PENDING, SENT, FAILED
    attempts        INT          NOT NULL DEFAULT 0,                    -- số lần đã thử gửi
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), -- PENDING: chưa tới mốc này thì chưa thử lại
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at         TIMESTAMP(3) NULL,
    last_error      VARCHAR(255) NULL,                                  -- tên loại lỗi lần thử gần nhất (không chứa dữ liệu)
    PRIMARY KEY (id),
    INDEX idx_outbox_messages_status_next_attempt (status, next_attempt_at),  -- job tìm PENDING tới hạn
    INDEX idx_outbox_messages_status_sent (status, sent_at)                   -- dọn SENT cũ
);
