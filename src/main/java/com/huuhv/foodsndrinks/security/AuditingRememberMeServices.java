package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AuthenticationClock;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * {@link TokenBasedRememberMeServices} có ghi audit log khi cookie remember-me bị từ chối (sai định dạng, sai chữ ký,
 * hết hạn, user không tồn tại hoặc bị khoá).
 * <p>
 * Các lỗi này bị {@code AbstractRememberMeServices.autoLogin} nuốt (chỉ log DEBUG rồi xoá cookie) trước khi tới
 * {@code ProviderManager}, nên không sinh event cho {@code AuthenticationAuditListener} — brute-force/replay cookie sẽ
 * không để lại dấu vết nếu không ghi ở đây. Remember-me thành công vẫn được audit qua event như bình thường.
 * <p>
 * Tài khoản bị khoá ({@code enabled = false}) bị từ chối bởi {@code userDetailsChecker}, được {@code autoLogin} gọi
 * <em>sau</em> {@link #processAutoLoginCookie} (tức chỉ khi chữ ký cookie hợp lệ), nên checker được bọc để ghi audit.
 * <p>
 * Mốc xác thực của session ({@link SessionAuthenticationTime}) được ghi ở {@link #createSuccessfulAuthentication} — chỉ
 * khi cookie hợp lệ VÀ tài khoản qua được {@code userDetailsChecker}, nên cookie của tài khoản bị khoá không tạo ra
 * session (một dòng {@code SPRING_SESSION}) nào.
 * <p>
 * Không phải bean tự quét (cần key) — được tạo trong {@code SecurityConfig#rememberMeServices}.
 */
public class AuditingRememberMeServices extends TokenBasedRememberMeServices {

    static final String METHOD = "REMEMBER_ME";

    private final AuditLogService auditLogService;
    private final AuthenticationClock authenticationClock;

    public AuditingRememberMeServices(String key, UserDetailsService userDetailsService,
                                      AuditLogService auditLogService, AuthenticationClock authenticationClock) {
        super(key, userDetailsService);
        this.auditLogService = auditLogService;
        this.authenticationClock = authenticationClock;
        setUserDetailsChecker(auditing(new AccountStatusUserDetailsChecker()));
    }

    @Override
    protected String[] decodeCookie(String cookieValue) throws InvalidCookieException {
        try {
            return super.decodeCookie(cookieValue);
        } catch (InvalidCookieException ex) {
            auditFailure(null, ex);
            throw ex;
        }
    }

    @Override
    protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request,
                                                 HttpServletResponse response) {
        try {
            return super.processAutoLoginCookie(cookieTokens, request, response);
        } catch (RememberMeAuthenticationException | UsernameNotFoundException | AccountStatusException ex) {
            // token[0] là username ghi trong cookie — AuditLogService chỉ ghi nếu tài khoản tồn tại
            auditFailure(cookieTokens.length > 0 ? cookieTokens[0] : null, ex);
            throw ex;
        }
    }

    /**
     * {@code autoLogin} gọi method này SAU {@code userDetailsChecker} (cookie hợp lệ, tài khoản đang hoạt động): session
     * sắp được tạo là một lần xác thực mới (chữ ký cookie chứa hash mật khẩu HIỆN TẠI) → ghi mốc để
     * {@code AccountStatusService} không coi nó là session cũ của tài khoản đã từng đổi mật khẩu.
     */
    @Override
    protected Authentication createSuccessfulAuthentication(HttpServletRequest request, UserDetails user) {
        Authentication authentication = super.createSuccessfulAuthentication(request, user);
        SessionAuthenticationTime.stamp(request, authenticationClock.now());
        return authentication;
    }

    /**
     * Cấp cookie remember-me mới cho {@code user} (nạp lại từ DB, mang hash mật khẩu mới) — dùng sau khi user tự đổi mật
     * khẩu: cookie cũ ký bằng hash cũ nên đã vô hiệu, không cấp lại thì trình duyệt đang "ghi nhớ đăng nhập" sẽ bị đăng
     * xuất khi session hết hạn.
     */
    public void reissueCookie(HttpServletRequest request, HttpServletResponse response, UserDetails user) {
        onLoginSuccess(request, response,
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private UserDetailsChecker auditing(UserDetailsChecker delegate) {
        return user -> {
            try {
                delegate.check(user);
            } catch (AccountStatusException ex) {
                auditFailure(user.getUsername(), ex);
                throw ex;
            }
        };
    }

    private void auditFailure(String username, RuntimeException ex) {
        auditLogService.loginFailure(METHOD, username, AuditLogService.toReasonCode(ex));
    }
}
