-- Thời điểm mật khẩu được đổi lần gần nhất (user tự đổi hoặc admin đặt lại); NULL = chưa đổi lần nào kể từ khi có cột
-- này. Session web được xác thực TRƯỚC thời điểm này bị huỷ ở request kế tiếp (AccountStatusFilter, issue #37).
-- DATETIME(6): app ghi/đọc theo UTC (Instant), không phụ thuộc time_zone của kết nối như TIMESTAMP.
ALTER TABLE users
    ADD COLUMN password_changed_at DATETIME(6) NULL;
