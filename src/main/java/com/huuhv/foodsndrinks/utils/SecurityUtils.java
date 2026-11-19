package com.huuhv.foodsndrinks.utils;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Quy tắc dùng chung để xác định người dùng hiện tại từ {@code SecurityContext} (MDC {@code user}, audit actor...). */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** @return username/email của người dùng đã xác thực; rỗng nếu chưa đăng nhập hoặc là anonymous */
    public static Optional<String> currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(auth.getName());
    }
}
