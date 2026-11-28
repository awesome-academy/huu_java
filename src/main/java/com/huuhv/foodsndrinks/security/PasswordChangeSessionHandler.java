package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Instant;

/**
 * Sau khi user TỰ đổi mật khẩu: giữ lại đúng session vừa đổi, mọi session khác của tài khoản bị
 * {@code AccountStatusFilter} huỷ ở request kế tiếp của chúng (lý do {@code PASSWORD_CHANGED}).
 * <p>
 * Hai bước, quanh lúc commit {@code users.password_changed_at}:
 * <ol>
 *   <li>{@link #stampCurrentSession} — TRƯỚC commit: ghi mốc xác thực = đúng {@code passwordChangedAt} sắp lưu (quy tắc
 *       huỷ là "xác thực TRƯỚC lúc đổi") thẳng vào kho session ({@link SessionAuthenticationTimeStore}). Spring Session
 *       chỉ lưu session khi response commit; nếu chỉ ghi vào session của request, request song song của chính session này
 *       (polling 2s của trang import, tab khác) rơi vào khoảng giữa lúc DB commit và lúc response lưu session sẽ thấy mốc
 *       cũ + mật khẩu mới → bị huỷ, người vừa đổi mật khẩu bị đá về {@code /login}. Ghi trước commit thì mọi request đọc
 *       được mật khẩu mới đều đọc được mốc mới; request đã nạp session từ trước đó được {@code AccountStatusFilter} đọc
 *       lại mốc trong kho trước khi huỷ.</li>
 *   <li>{@link #renewCurrentSession} — SAU commit: đổi session id (id cũ có thể đã lộ, đây là lý do thường gặp của việc đổi
 *       mật khẩu) và cấp lại cookie remember-me (chữ ký của nó chứa hash mật khẩu cũ).</li>
 * </ol>
 * Commit lỗi sau bước 1 thì session mang mốc mới hơn thời điểm xác thực thật — vô hại: chính session đó vốn hợp lệ, và
 * lần đổi mật khẩu sau (mốc theo đồng hồ DB, luôn muộn hơn) vẫn huỷ được nó.
 * <p>
 * Admin đặt lại mật khẩu thì không gọi lớp này: mọi session của tài khoản đó đều bị huỷ.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeSessionHandler {

    private final AuditingRememberMeServices rememberMeServices;
    private final CustomUserDetailsService userDetailsService;
    private final SessionAuthenticationTimeStore sessionAuthenticationTimeStore;

    /**
     * Gọi TRONG transaction đổi mật khẩu, trước commit ({@code UserService.updateProfile}), trên request của chính user.
     *
     * @param passwordChangedAt giá trị sắp ghi vào {@code users.password_changed_at}
     */
    public void stampCurrentSession(HttpServletRequest request, Instant passwordChangedAt) {
        HttpSession session = request.getSession(false);
        // Session chưa có trong kho (vừa tạo ở request này) thì chưa request nào khác thấy nó: ghi vào session của
        // request là đủ, được lưu cùng response
        if (session == null || !sessionAuthenticationTimeStore.persist(session.getId(), passwordChangedAt)) {
            SessionAuthenticationTime.stamp(request, passwordChangedAt);
        }
    }

    /**
     * Gọi SAU khi transaction đổi mật khẩu đã commit, trên request của chính user đó.
     *
     * @param username {@code Principal#getName()} của session hiện tại
     */
    public void renewCurrentSession(HttpServletRequest request, HttpServletResponse response, String username) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        if (WebUtils.getCookie(request, AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY) != null) {
            rememberMeServices.reissueCookie(request, response, userDetailsService.loadUserByUsername(username));
        }
    }
}
