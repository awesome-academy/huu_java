package com.huuhv.foodsndrinks.dto.message;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Message JMS trên topic {@code order.status-changed} (alias {@code orderStatusChanged} trong {@code MessageTypes}),
 * ghi vào outbox trong cùng transaction với việc đổi trạng thái ({@code OrderService.updateStatus}) → chỉ gửi sau commit.
 * <p>
 * {@code username} là username local của chủ đơn, lấy từ DB (không từ request) — cũng là {@code Principal#getName()} của
 * mọi phiên đăng nhập (form, remember-me, Google/Facebook/X qua thuộc tính {@code local_username}), nên
 * {@code convertAndSendToUser(username, ...)} tới đúng các phiên của chủ đơn.
 * <p>
 * Như {@link OrderPlacedMessage}: chỉ <b>thêm</b> field, không đổi tên/xoá/đổi kiểu (instance cũ/mới cùng chạy khi
 * rolling deploy). Trạng thái mới chưa có ở instance cũ → không đọc được → listener bỏ (WARN), trang tự tải lại khi nối lại.
 *
 * @param orderId  đơn đã đổi trạng thái
 * @param username username local của chủ đơn
 * @param status   trạng thái mới
 */
public record OrderStatusChangedMessage(
        @NotNull @Positive Long orderId,
        @NotBlank String username,
        @NotNull OrderStatus status) {
}
