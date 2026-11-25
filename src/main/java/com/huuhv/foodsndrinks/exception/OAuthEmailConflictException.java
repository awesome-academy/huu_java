package com.huuhv.foodsndrinks.exception;

import lombok.Getter;

/**
 * Đăng nhập OAuth2 mang email đã thuộc một tài khoản local nhưng không được phép tự liên kết (provider không xác minh
 * email, hoặc tài khoản có sẵn có mật khẩu — xem {@code UserService.provisionOAuthUser}). Tầng security đổi exception
 * này thành {@code OAuth2EmailConflictException}.
 */
@Getter
public class OAuthEmailConflictException extends RuntimeException {

    /** Username của tài khoản đang giữ email — chỉ để ghi audit, không hiển thị cho người dùng. */
    private final String existingUsername;

    public OAuthEmailConflictException(String existingUsername) {
        super("OAuth2 email belongs to an account that cannot be linked automatically");
        this.existingUsername = existingUsername;
    }
}
