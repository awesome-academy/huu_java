package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.core.userdetails.UserDetails;
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
 * Không phải bean tự quét (cần key) — được tạo trong {@code SecurityConfig#rememberMeServices}.
 */
public class AuditingRememberMeServices extends TokenBasedRememberMeServices {

    static final String METHOD = "REMEMBER_ME";

    private final AuditLogService auditLogService;

    public AuditingRememberMeServices(String key, UserDetailsService userDetailsService,
                                      AuditLogService auditLogService) {
        super(key, userDetailsService);
        this.auditLogService = auditLogService;
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

    private void auditFailure(String username, RuntimeException ex) {
        String reason = AuditLogService.toReasonCode(ex.getClass().getSimpleName().replace("Exception", ""));
        auditLogService.loginFailure(METHOD, username, reason);
    }
}
