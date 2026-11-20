package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.exception.OAuthEmailConflictException;
import lombok.Getter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

import java.io.Serial;

/**
 * Email của tài khoản OAuth2 đã thuộc một tài khoản local không được phép tự liên kết (chống chiếm trước tài khoản —
 * xem {@code UserService.provisionOAuthUser}). {@link OAuth2LoginFailureHandler} ghi audit {@code EMAIL_CONFLICT} và
 * đưa về {@code /login?oauthEmailExists=true} (gợi ý đăng nhập bằng mật khẩu).
 */
@Getter
public class OAuth2EmailConflictException extends OAuth2AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Username của tài khoản đang giữ email — chỉ để ghi audit. */
    private final String existingUsername;

    public OAuth2EmailConflictException(OAuthEmailConflictException cause) {
        super(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED), cause.getMessage(), cause);
        this.existingUsername = cause.getExistingUsername();
    }
}
