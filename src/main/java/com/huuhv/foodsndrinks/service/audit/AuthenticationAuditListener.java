package com.huuhv.foodsndrinks.service.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UrlPathHelper;

/**
 * Ghi audit log cho đăng nhập dựa trên event mà {@code ProviderManager} publish (qua
 * {@code DefaultAuthenticationEventPublisher}), nên áp dụng cho form login, {@code POST /api/v1/auth/login},
 * remember-me thành công và đăng nhập OAuth2 thành công mà không phải sửa từng luồng.
 * <p>
 * Những lỗi không đi tới {@code ProviderManager} nên không sinh event, được ghi ở nơi khác:
 * <ul>
 *   <li>lỗi OAuth2 (Spring Security không map {@code OAuth2AuthenticationException} sang event, và lỗi như người dùng
 *       từ chối cấp quyền xảy ra trước đó) → {@code security.OAuth2LoginFailureHandler}. Event lỗi có token OAuth2
 *       (nếu có) bị bỏ qua ở đây để không ghi trùng;</li>
 *   <li>cookie remember-me sai/hết hạn/giả mạo → {@code security.AuditingRememberMeServices}.</li>
 * </ul>
 * Request mang JWT không đi qua {@code ProviderManager} ({@code JwtAuthenticationFilter} tự đặt SecurityContext),
 * nên không sinh audit log cho mỗi lần gọi API.
 */
@Component
@RequiredArgsConstructor
public class AuthenticationAuditListener {

    private final AuditLogService auditLogService;

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        auditLogService.loginSuccess(loginMethod(auth), auth.getName());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        if (isOAuth2(auth)) {
            return;
        }
        auditLogService.loginFailure(loginMethod(auth), auth.getName(), reason(event));
    }

    static String loginMethod(Authentication auth) {
        if (auth instanceof OAuth2LoginAuthenticationToken login) {
            return "OAUTH2:" + login.getClientRegistration().getRegistrationId();
        }
        if (auth instanceof OAuth2AuthenticationToken oauth2) {
            return "OAUTH2:" + oauth2.getAuthorizedClientRegistrationId();
        }
        if (auth instanceof RememberMeAuthenticationToken) {
            return "REMEMBER_ME";
        }
        return isApiRequest() ? "API" : "FORM";
    }

    private static boolean isOAuth2(Authentication auth) {
        return auth instanceof OAuth2LoginAuthenticationToken
                || auth instanceof OAuth2AuthorizationCodeAuthenticationToken
                || auth instanceof OAuth2AuthenticationToken;
    }

    private static boolean isApiRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && UrlPathHelper.defaultInstance.getPathWithinApplication(attrs.getRequest()).startsWith("/api/");
    }

    /** AuthenticationFailureBadCredentialsEvent → BAD_CREDENTIALS, AuthenticationFailureDisabledEvent → DISABLED, ... */
    static String reason(AbstractAuthenticationFailureEvent event) {
        return AuditLogService.toReasonCode(event.getClass().getSimpleName()
                .replace("AuthenticationFailure", "")
                .replace("Event", ""));
    }
}
