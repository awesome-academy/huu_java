package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Chain web: huỷ session của tài khoản đã bị admin khoá, bị xoá/đổi username, bị đổi role hoặc đã đổi mật khẩu kể từ lúc
 * session đăng nhập — có hiệu lực ngay ở request tiếp theo thay vì chờ session hết hạn (30 phút không hoạt động).
 * <p>
 * Kiểm tra DB ở mỗi request đã đăng nhập (giống {@code JwtAuthenticationFilter} của chain API) thay vì huỷ session lúc
 * khoá qua {@code SessionRegistry}: registry nằm trong bộ nhớ từng instance và không biết session tạo bởi remember-me.
 * Một query 3 cột theo index UNIQUE; bỏ qua file tĩnh. Mốc xác thực của session: {@link SessionAuthenticationTime}.
 * <p>
 * Trước khi huỷ vì {@code PASSWORD_CHANGED}, mốc được đọc lại từ kho session ({@link SessionAuthenticationTimeStore}):
 * request này có thể đã nạp session ngay trước khi một request song song của CHÍNH session đó đổi mật khẩu và ghi mốc mới
 * thẳng vào kho ({@link PasswordChangeSessionHandler}) — huỷ theo bản đã nạp sẽ đá người vừa đổi mật khẩu ra ngoài.
 * <p>
 * Khi huỷ: ghi audit {@code SESSION_REVOKED}, xoá cookie remember-me, invalidate session, xoá SecurityContext rồi cho
 * request đi tiếp như khách — trang public vẫn hiển thị, trang cần đăng nhập thì entry point đưa về {@code /login}
 * (giống session hết hạn, kể cả với {@code fetch()}).
 * <p>
 * Đặt trước {@code AnonymousAuthenticationFilter} (sau remember-me) trong {@code SecurityConfig}. Không phải
 * {@code @Component} để Boot không tự đăng ký thành servlet filter chạy ngoài chain.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final List<String> STATIC_PREFIXES =
            List.of("/css/", "/js/", "/images/", "/webjars/", "/uploads/", "/favicon.ico");

    private final AccountStatusService accountStatusService;
    private final AuditLogService auditLogService;
    private final List<LogoutHandler> logoutHandlers;
    private final SessionAuthenticationTimeStore sessionAuthenticationTimeStore;

    /** @param rememberMeServices xoá cookie remember-me khi huỷ session */
    public AccountStatusFilter(AccountStatusService accountStatusService, AuditLogService auditLogService,
                               LogoutHandler rememberMeServices,
                               SessionAuthenticationTimeStore sessionAuthenticationTimeStore) {
        this.accountStatusService = accountStatusService;
        this.auditLogService = auditLogService;
        this.logoutHandlers = List.of(rememberMeServices, new SecurityContextLogoutHandler());
        this.sessionAuthenticationTimeStore = sessionAuthenticationTimeStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return STATIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            revocationReason(request.getSession(false), auth)
                    .ifPresent(reason -> revoke(request, response, auth, reason));
        }
        filterChain.doFilter(request, response);
    }

    private Optional<String> revocationReason(HttpSession session, Authentication auth) {
        Instant authenticatedAt = SessionAuthenticationTime.of(session);
        Optional<String> reason =
                accountStatusService.sessionRevocationReason(auth.getName(), auth.getAuthorities(), authenticatedAt);
        if (session == null || !reason.equals(Optional.of(AccountStatusService.PASSWORD_CHANGED))) {
            return reason;
        }
        Instant stored = sessionAuthenticationTimeStore.reload(session.getId());
        if (stored == null || stored.equals(authenticatedAt)) {
            return reason;
        }
        return accountStatusService.sessionRevocationReason(auth.getName(), auth.getAuthorities(), stored);
    }

    private void revoke(HttpServletRequest request, HttpServletResponse response, Authentication auth, String reason) {
        auditLogService.sessionRevoked(auth.getName(), reason);
        for (LogoutHandler handler : logoutHandlers) {
            handler.logout(request, response, auth);
        }
    }
}
