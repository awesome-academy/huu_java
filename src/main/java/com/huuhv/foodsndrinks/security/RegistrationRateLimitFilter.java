package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.security.RegistrationRateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Chain web: giới hạn {@code POST /register} theo IP ({@link RegistrationRateLimitService}). Phải là filter (không đặt
 * trong controller) vì validation của DTO — gồm cả lần gọi Pwned Passwords — chạy trước khi vào method controller.
 * Vượt giới hạn → {@code 303} về {@code /register?throttled=true} (trang hiển thị thông báo), không xử lý form.
 * <p>
 * Không phải {@code @Component} để Boot không tự đăng ký thành servlet filter chạy ngoài chain.
 */
public class RegistrationRateLimitFilter extends OncePerRequestFilter {

    static final String REGISTER_PATH = "/register";
    static final String THROTTLED_URL = "/register?throttled=true";

    private final RegistrationRateLimitService registrationRateLimitService;

    public RegistrationRateLimitFilter(RegistrationRateLimitService registrationRateLimitService) {
        this.registrationRateLimitService = registrationRateLimitService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !REGISTER_PATH.equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (registrationRateLimitService.tryAcquire(request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", request.getContextPath() + THROTTLED_URL);
    }
}
