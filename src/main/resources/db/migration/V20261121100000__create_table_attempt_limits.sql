-- Bộ đếm chống brute-force đăng nhập / đổi mật khẩu và giới hạn tần suất đăng ký (service/security/AttemptLimiter),
-- dùng chung giữa mọi instance sau load balancer và không mất khi restart/deploy.
-- Thời gian lưu dạng epoch millis (BIGINT) do app truyền vào, không phụ thuộc múi giờ của JVM/MySQL.
-- Bản ghi hết hạn (cửa sổ đếm và khoá đều đã qua) được job AttemptLimitCleanupJob xoá định kỳ.
CREATE TABLE attempt_limits (
    limiter        VARCHAR(32) NOT NULL,             -- phạm vi đếm: IP, IP_USER, ACCOUNT, PASSWORD_CHANGE, REGISTRATION
    key_hash       BINARY(32)  NOT NULL,             -- HMAC-SHA256 của key (IP, tên đăng nhập người dùng gõ...) — không lưu chuỗi gốc
    attempts       INT         NOT NULL,             -- số lần thử đang chạy + số lần sai trong cửa sổ hiện tại
    window_ends_at BIGINT      NOT NULL,             -- hết cửa sổ đếm (cố định, tính từ lần thử đầu tiên)
    locked_until   BIGINT      NOT NULL DEFAULT 0,   -- hết khoá; 0 = chưa từng bị khoá
    expires_at     BIGINT AS (GREATEST(window_ends_at, locked_until)) STORED,
    PRIMARY KEY (limiter, key_hash),
    INDEX idx_attempt_limits_expires_at (expires_at)
);
