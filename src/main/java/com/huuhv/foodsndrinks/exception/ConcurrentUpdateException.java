package com.huuhv.foodsndrinks.exception;

/**
 * Bản ghi vừa bị người khác sửa giữa lúc đọc và lúc ghi (UPDATE có điều kiện trả về 0 dòng) — thay đổi của request này
 * KHÔNG được lưu. Thông điệp viết cho người dùng ("tải lại trang"); handler trả 409.
 */
public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String message) {
        super(message);
    }
}
