package com.huuhv.foodsndrinks.security;

import lombok.Getter;
import org.springframework.security.authentication.LockedException;

/**
 * Đăng nhập bị tạm khoá vì sai quá nhiều lần (xem {@code LoginAttemptService}). Không gắn với việc tài khoản có tồn
 * tại hay không — tên đăng nhập không tồn tại cũng bị khoá y hệt.
 * <p>
 * Kế thừa {@link LockedException} cho đúng ngữ nghĩa, nhưng {@code DefaultAuthenticationEventPublisher} map exception
 * → event theo đúng tên class nên class này được đăng ký riêng (bean {@code authenticationEventPublisher} trong
 * {@code SecurityConfig}) để audit vẫn ghi được {@code event.reason=LOGIN_THROTTLED}.
 */
@Getter
public class LoginThrottledException extends LockedException {

    /** Số giây còn lại tới khi hết khoá (header {@code Retry-After} của API). */
    private final long retryAfterSeconds;

    public LoginThrottledException(long retryAfterSeconds) {
        super("Login temporarily blocked after too many failed attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
