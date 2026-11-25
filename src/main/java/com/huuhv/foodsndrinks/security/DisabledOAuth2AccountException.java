package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.entity.User;
import lombok.Getter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

import java.io.Serial;

/**
 * Đăng nhập OAuth2/OIDC vào tài khoản local đã bị admin khoá ({@code isActive = false}).
 * <p>
 * Đăng nhập OAuth2 không đi qua {@code CustomUserDetailsService} nên không có bước kiểm tra {@code enabled} như form/API;
 * {@link CustomOidcUserService} và {@link CustomOAuth2UserService} gọi {@link #rejectIfDisabled(User)} sau khi tìm ra
 * tài khoản local. Exception đi tới {@link OAuth2LoginFailureHandler} → redirect {@code /login?error=true} (thông báo
 * chung như mọi lỗi đăng nhập) và audit ghi {@code LOGIN_FAILURE reason=DISABLED} kèm username, giống form/API.
 * <p>
 * Mã lỗi OAuth2 là {@code access_denied} (chuẩn RFC 6749); username chỉ để ghi audit, không đưa vào message hay
 * {@link OAuth2Error} (không hiển thị cho người dùng).
 */
@Getter
public class DisabledOAuth2AccountException extends OAuth2AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String username;

    public DisabledOAuth2AccountException(String username) {
        super(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED), "Account is disabled");
        this.username = username;
    }

    /** Cùng quy tắc với {@code CustomUserDetailsService}: chỉ {@code isActive = false} mới bị coi là khoá. */
    static void rejectIfDisabled(User user) {
        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new DisabledOAuth2AccountException(user.getUsername());
        }
    }
}
