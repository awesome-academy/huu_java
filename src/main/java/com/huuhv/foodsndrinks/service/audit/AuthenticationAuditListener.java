package com.huuhv.foodsndrinks.service.audit;

import jakarta.servlet.http.HttpServletRequest;
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

import java.util.Locale;

/**
 * Ghi audit log cho đăng nhập dựa trên event mà {@code ProviderManager} publish (qua
 * {@code DefaultAuthenticationEventPublisher}), nên áp dụng cho form login, {@code POST /api/v1/auth/login},
 * remember-me và đăng nhập OAuth2 thành công mà không phải sửa từng luồng.
 * <p>
 * Ngoại lệ: Spring Security không map {@code OAuth2AuthenticationException} sang event nào, và lỗi OAuth2
 * (vd. người dùng từ chối cấp quyền) còn xảy ra trước khi tới {@code ProviderManager} — lỗi OAuth2 được ghi ở
 * {@code security.OAuth2LoginFailureHandler}. Event lỗi có token OAuth2 (nếu có) bị bỏ qua ở đây để không ghi trùng.
 * <p>
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
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return path.startsWith("/api/");
        }
        return false;
    }

    /** AuthenticationFailureBadCredentialsEvent → BAD_CREDENTIALS, AuthenticationFailureDisabledEvent → DISABLED, ... */
    static String reason(AbstractAuthenticationFailureEvent event) {
        String name = event.getClass().getSimpleName()
                .replace("AuthenticationFailure", "")
                .replace("Event", "");
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
