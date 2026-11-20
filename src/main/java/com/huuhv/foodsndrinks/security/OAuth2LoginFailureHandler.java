package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Set;

/**
 * Failure handler của {@code oauth2Login}: ghi audit log rồi xử lý như {@code failureUrl("/login?error=true")}.
 * <p>
 * Cần handler riêng vì lỗi OAuth2 không sinh event {@code AbstractAuthenticationFailureEvent} (xem
 * {@code AuthenticationAuditListener}). Chỉ ghi provider + mã lỗi OAuth2 ({@code access_denied},
 * {@code invalid_token_response}...) — không ghi error description, authorization code hay token.
 * <p>
 * Cả hai giá trị đều có thể do kẻ tấn công chọn (provider lấy từ path {@code /login/oauth2/code/<id>}, mã lỗi từ query
 * param {@code error} của callback), nên chỉ ghi nguyên văn khi khớp whitelist: provider phải có trong
 * {@link ClientRegistrationRepository} (ngược lại ghi {@value #UNKNOWN_PROVIDER}), mã lỗi phải là mã chuẩn OAuth2/OIDC
 * hoặc mã nội bộ của Spring Security (ngược lại ghi {@value #OTHER_ERROR}).
 * <p>
 * Ngoại lệ: tài khoản local đã bị khoá ({@link DisabledOAuth2AccountException}) được ghi {@value #DISABLED_REASON} kèm
 * username, giống đăng nhập form/API bị chặn vì tài khoản bị khoá. Email trùng tài khoản không được tự liên kết
 * ({@link OAuth2EmailConflictException}) được ghi {@value #EMAIL_CONFLICT_REASON} kèm username của tài khoản đó và đưa về
 * {@value #EMAIL_CONFLICT_URL}.
 */
@Component
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    static final String FAILURE_URL = "/login?error=true";
    static final String UNKNOWN_PROVIDER = "unknown";
    static final String OTHER_ERROR = "OTHER";
    static final String DISABLED_REASON = "DISABLED";
    static final String EMAIL_CONFLICT_REASON = "EMAIL_CONFLICT";
    static final String EMAIL_CONFLICT_URL = "/login?oauthEmailExists=true";
    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";

    private static final Set<String> KNOWN_ERROR_CODES = Set.of(
            // RFC 6749 / RFC 6750 / OIDC Core
            OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, OAuth2ErrorCodes.ACCESS_DENIED,
            OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE, OAuth2ErrorCodes.INVALID_SCOPE,
            OAuth2ErrorCodes.INSUFFICIENT_SCOPE, OAuth2ErrorCodes.INVALID_TOKEN, OAuth2ErrorCodes.SERVER_ERROR,
            OAuth2ErrorCodes.TEMPORARILY_UNAVAILABLE, OAuth2ErrorCodes.INVALID_CLIENT, OAuth2ErrorCodes.INVALID_GRANT,
            OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, OAuth2ErrorCodes.UNSUPPORTED_TOKEN_TYPE,
            OAuth2ErrorCodes.INVALID_REDIRECT_URI,
            "interaction_required", "login_required", "consent_required", "account_selection_required",
            // Mã nội bộ của Spring Security OAuth2 Client (khai báo private trong các filter/provider)
            "authorization_request_not_found", "client_registration_not_found", "invalid_state_parameter",
            "invalid_token_response", "invalid_id_token", "invalid_nonce", "invalid_user_info_response",
            "missing_user_info_uri", "missing_user_name_attribute");

    private final AuditLogService auditLogService;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public OAuth2LoginFailureHandler(AuditLogService auditLogService,
                                     ClientRegistrationRepository clientRegistrationRepository) {
        this.auditLogService = auditLogService;
        this.clientRegistrationRepository = clientRegistrationRepository;
        setDefaultFailureUrl(FAILURE_URL);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String method = "OAUTH2:" + registrationId(request);
        if (exception instanceof DisabledOAuth2AccountException disabled) {
            // Cùng reason với DisabledException của form/API; username là của tài khoản local (không do client gửi)
            auditLogService.loginFailure(method, disabled.getUsername(), DISABLED_REASON);
        } else if (exception instanceof OAuth2EmailConflictException conflict) {
            // Email trùng tài khoản không được tự liên kết: báo người dùng đăng nhập bằng mật khẩu thay vì lỗi chung
            auditLogService.loginFailure(method, conflict.getExistingUsername(), EMAIL_CONFLICT_REASON);
            getRedirectStrategy().sendRedirect(request, response, EMAIL_CONFLICT_URL);
            return;
        } else {
            auditLogService.loginFailure(method, null, reason(exception));
        }
        super.onAuthenticationFailure(request, response, exception);
    }

    private String registrationId(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.startsWith(CALLBACK_PREFIX)) {
            return UNKNOWN_PROVIDER;
        }
        String id = path.substring(CALLBACK_PREFIX.length());
        // InMemoryClientRegistrationRepository ném IllegalArgumentException với id rỗng
        return !id.isBlank() && clientRegistrationRepository.findByRegistrationId(id) != null ? id : UNKNOWN_PROVIDER;
    }

    private static String reason(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            String code = oauth2.getError().getErrorCode();
            return KNOWN_ERROR_CODES.contains(code) ? code : OTHER_ERROR;
        }
        return AuditLogService.toReasonCode(exception);
    }
}
