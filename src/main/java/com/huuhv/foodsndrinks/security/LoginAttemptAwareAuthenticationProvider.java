package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService.LoginAttempt;
import com.huuhv.foodsndrinks.utils.RequestUtils;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Provider username/mật khẩu dùng chung cho form login và {@code POST /api/v1/auth/login} (cả hai đi qua
 * {@code AuthenticationManager} toàn cục), bổ sung chống brute-force ({@link LoginAttemptService}):
 * <ol>
 *   <li>trước khi tra user: kiểm tra khoá theo IP, cặp (IP, tên nhập), tên nhập, rồi <b>giữ chỗ</b> một lần thử — nhiều
 *       request song song không cùng lọt qua lúc bộ đếm còn thấp (tên không tồn tại bị khoá y hệt tên có thật);</li>
 *   <li>sau khi tra được user, trước khi so BCrypt: kiểm tra + giữ chỗ theo username thật;</li>
 *   <li>sai mật khẩu → tính là sai; đúng → xoá bộ đếm; lỗi khác (tài khoản bị khoá...) → trả lại lần giữ chỗ.</li>
 * </ol>
 * Key phải là chuỗi người dùng nhập (không qua {@code AuthenticationSuccessEvent}): event thành công chỉ còn username
 * chuẩn, đăng nhập bằng email sẽ không bao giờ xoá được bộ đếm của email.
 * <p>
 * Trạng thái tài khoản (disabled...) được kiểm tra SAU khi so mật khẩu (mặc định của Spring là trước): nếu không,
 * ai cũng dò được tài khoản nào tồn tại và đang bị khoá mà không cần biết mật khẩu.
 */
public class LoginAttemptAwareAuthenticationProvider extends DaoAuthenticationProvider {

    /** Lần đăng nhập đang chạy trên thread hiện tại — để {@link #additionalAuthenticationChecks} thêm username thật. */
    private static final ThreadLocal<LoginAttempt> CURRENT_ATTEMPT = new ThreadLocal<>();

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
        LoginAttempt attempt = loginAttemptService.begin(authentication.getName(), clientIp(authentication));
        CURRENT_ATTEMPT.set(attempt);
        boolean finished = false;
        try {
            Authentication result = super.authenticate(authentication);
            loginAttemptService.succeeded(attempt);
            finished = true;
            return result;
        } catch (BadCredentialsException ex) {
            // Gồm cả tên không tồn tại: DaoAuthenticationProvider đổi UsernameNotFoundException thành BadCredentials
            loginAttemptService.failed(attempt);
            finished = true;
            throw ex;
        } finally {
            CURRENT_ATTEMPT.remove();
            if (!finished) {
                loginAttemptService.abandoned(attempt);
            }
        }
    }

    @Override
    protected void additionalAuthenticationChecks(UserDetails userDetails,
                                                  UsernamePasswordAuthenticationToken authentication)
            throws AuthenticationException {
        LoginAttempt attempt = CURRENT_ATTEMPT.get();
        if (attempt != null) {
            loginAttemptService.addAccount(attempt, userDetails.getUsername());
        }
        super.additionalAuthenticationChecks(userDetails, authentication);
    }

    /** Form login có {@link WebAuthenticationDetails}; API login tự tạo token nên lấy từ request hiện tại. */
    private static String clientIp(Authentication authentication) {
        if (authentication.getDetails() instanceof WebAuthenticationDetails details && details.getRemoteAddress() != null) {
            return details.getRemoteAddress();
        }
        return RequestUtils.currentClientIp().orElse(null);
    }
}
