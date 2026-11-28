package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Instant;

/**
 * Sau khi user TỰ đổi mật khẩu: giữ lại đúng session vừa đổi, mọi session khác của tài khoản bị
 * {@code AccountStatusFilter} huỷ ở request kế tiếp của chúng (lý do {@code PASSWORD_CHANGED}).
 * <p>
 * Session hiện tại được giữ bằng cách ghi lại mốc xác thực = đúng {@code passwordChangedAt} vừa lưu (quy tắc huỷ là
 * "xác thực TRƯỚC lúc đổi"), kèm đổi session id — id cũ có thể đã lộ, đây là lý do thường gặp của việc đổi mật khẩu.
 * Cookie remember-me (nếu trình duyệt đang có) được cấp lại vì chữ ký của nó chứa hash mật khẩu cũ.
 * <p>
 * Admin đặt lại mật khẩu thì không gọi lớp này: mọi session của tài khoản đó đều bị huỷ.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeSessionHandler {

    private final AuditingRememberMeServices rememberMeServices;
    private final CustomUserDetailsService userDetailsService;

    /**
     * Gọi SAU khi transaction đổi mật khẩu đã commit, trên request của chính user đó.
     *
     * @param username          {@code Principal#getName()} của session hiện tại
     * @param passwordChangedAt giá trị vừa ghi vào {@code users.password_changed_at}
     */
    public void keepCurrentSession(HttpServletRequest request, HttpServletResponse response, String username,
                                   Instant passwordChangedAt) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SessionAuthenticationTime.stamp(request, passwordChangedAt);
        if (WebUtils.getCookie(request, AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY) != null) {
            rememberMeServices.reissueCookie(request, response, userDetailsService.loadUserByUsername(username));
        }
    }
}
