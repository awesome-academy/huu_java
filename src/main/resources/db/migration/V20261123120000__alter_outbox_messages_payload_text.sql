-- outbox_messages.payload phải được gửi lên JMS đúng từng byte đã serialize. Kiểu JSON của MySQL phân tích rồi lưu dạng
-- nhị phân: đổi thứ tự key, bỏ khoảng trắng và lưu số thập phân dạng DOUBLE (BigDecimal 150000.00 đọc ra thành
-- 150000.0) → đổi sang MEDIUMTEXT, vẫn bắt buộc là JSON hợp lệ bằng CHECK (MySQL >= 8.0.16). JSON_EXTRACT(payload, ...)
-- vẫn dùng được trên cột chữ.
-- Thêm giá trị status DEAD_LETTERED (OutboxStatus): message đã gửi nhưng vào DLQ — job dọn dẹp chỉ xoá SENT nên bản ghi
-- được giữ để gửi lại (cột VARCHAR(16), không cần đổi schema).
ALTER TABLE outbox_messages
    MODIFY payload MEDIUMTEXT NOT NULL,
    ADD CONSTRAINT chk_outbox_messages_payload_json CHECK (JSON_VALID(payload));
