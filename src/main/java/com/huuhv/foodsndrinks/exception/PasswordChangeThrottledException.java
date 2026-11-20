package com.huuhv.foodsndrinks.exception;

/**
 * Nhập sai mật khẩu hiện tại quá nhiều lần khi tự đổi mật khẩu (xem {@code LoginAttemptService}). Session có thể đã bị
 * chiếm nên controller phải đăng xuất (huỷ session + cookie remember-me) và bắt đăng nhập lại.
 */
public class PasswordChangeThrottledException extends RuntimeException {

    public PasswordChangeThrottledException() {
        super("Too many wrong current-password attempts");
    }
}
