package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provider username/mật khẩu dùng chung cho form login và {@code POST /api/v1/auth/login} (cả hai đi qua
 * {@code AuthenticationManager} toàn cục), bổ sung chống brute-force:
 * <ul>
 *   <li>kiểm tra khoá TRƯỚC khi tra user → tên không tồn tại bị khoá y hệt tên có thật;</li>
 *   <li>đếm/xoá bộ đếm ngay tại đây (không qua {@code AuthenticationSuccessEvent}) vì key phải là chuỗi người dùng
 *       nhập: event thành công chỉ còn username chuẩn, đăng nhập bằng email sẽ không bao giờ xoá được bộ đếm của email.</li>
 * </ul>
 * Trạng thái tài khoản (disabled...) được kiểm tra SAU khi so mật khẩu (mặc định của Spring là trước): nếu không,
 * ai cũng dò được tài khoản nào tồn tại và đang bị khoá mà không cần biết mật khẩu.
 */
public class LoginAttemptAwareAuthenticationProvider extends DaoAuthenticationProvider {

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptAwareAuthenticationProvider(UserDetailsService userDetailsService,
                                                   PasswordEncoder passwordEncoder,
                                                   LoginAttemptService loginAttemptService) {
        super(userDetailsService);
        setPasswordEncoder(passwordEncoder);
        setPreAuthenticationChecks(user -> { });
        setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String loginId = authentication.getName();
        loginAttemptService.checkNotBlocked(loginId);
        try {
            Authentication result = super.authenticate(authentication);
            loginAttemptService.loginSucceeded(loginId);
            return result;
        } catch (BadCredentialsException ex) {
            // Gồm cả tên không tồn tại: DaoAuthenticationProvider đổi UsernameNotFoundException thành BadCredentials
            loginAttemptService.loginFailed(loginId);
            throw ex;
        }
    }
}
