package com.huuhv.foodsndrinks.validation;

/**
 * Validation group chỉ chứa {@link NotCompromisedPassword} (gọi dịch vụ Pwned Passwords bên ngoài). DTO có mật khẩu mới
 * khai báo {@code @GroupSequence({TheDto.class, PasswordBreachCheck.class})}: group này chỉ chạy khi mọi ràng buộc khác
 * của form đã đạt, để request sai field khác (thường gặp khi spam form) không tốn một lần gọi ra ngoài.
 */
public interface PasswordBreachCheck {
}
