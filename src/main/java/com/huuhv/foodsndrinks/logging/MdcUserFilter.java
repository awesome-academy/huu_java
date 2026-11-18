package com.huuhv.foodsndrinks.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Đặt username của người dùng đã đăng nhập vào MDC (pattern log in {@code %X{user}}).
 * <p>
 * Chạy ngay sau {@code springSecurityFilterChain} (order {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} + 1):
 * lúc này SecurityContext đã được nạp từ session / remember-me / JWT, và vẫn còn nguyên cho tới khi controller
 * trả về. Log phát ra bên trong chính các security filter (vd. lúc đăng nhập) chưa có {@code user} — audit log
 * luôn ghi username tường minh trong message nên không phụ thuộc vào giá trị này.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1)
public class MdcUserFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "user";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get(MDC_KEY);
        String username = currentUsername();
        if (username != null) {
            MDC.put(MDC_KEY, LogSanitizer.sanitize(username));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
