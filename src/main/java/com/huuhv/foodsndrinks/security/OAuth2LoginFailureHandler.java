package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Failure handler của {@code oauth2Login}: ghi audit log rồi xử lý như {@code failureUrl("/login?error=true")}.
 * <p>
 * Cần handler riêng vì lỗi OAuth2 không sinh event {@code AbstractAuthenticationFailureEvent} (xem
 * {@code AuthenticationAuditListener}). Chỉ ghi provider + mã lỗi OAuth2 ({@code access_denied},
 * {@code invalid_token_response}...) — không ghi error description, authorization code hay token.
 */
@Component
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    static final String FAILURE_URL = "/login?error=true";
    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";

    private final AuditLogService auditLogService;

    public OAuth2LoginFailureHandler(AuditLogService auditLogService) {
        super(FAILURE_URL);
        this.auditLogService = auditLogService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        auditLogService.loginFailure("OAUTH2:" + registrationId(request), null, reason(exception));
        super.onAuthenticationFailure(request, response, exception);
    }

    private static String registrationId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(CALLBACK_PREFIX) ? path.substring(CALLBACK_PREFIX.length()) : "unknown";
    }

    private static String reason(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            return oauth2.getError().getErrorCode();
        }
        return exception.getClass().getSimpleName();
    }
}
