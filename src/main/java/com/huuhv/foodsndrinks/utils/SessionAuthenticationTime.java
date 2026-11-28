package com.huuhv.foodsndrinks.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.time.Instant;

/**
 * Mốc "session này được xác thực lúc nào", lưu ngay trong session HTTP — để huỷ các session đăng nhập TRƯỚC khi mật khẩu
 * được đổi ({@code AccountStatusService}, issue #37).
 * <p>
 * Ghi ở mọi đường tạo ra session đã đăng nhập: form + OAuth2 ({@code SecurityConfig#customAuthenticationSuccessHandler}),
 * remember-me ({@code AuditingRememberMeServices}), và session được giữ lại sau khi chính nó đổi mật khẩu
 * ({@code PasswordChangeSessionHandler}). Đường đăng nhập mới mà quên ghi thì session của tài khoản đã từng đổi mật khẩu
 * bị huỷ ngay ở request kế tiếp (thiếu mốc = coi như cũ) — lỗi lộ ra ngay, không âm thầm bỏ qua việc thu hồi.
 * <p>
 * Giá trị là {@code Long} epoch-millis (không phải {@link Instant}) để serialize được ở mọi kho session. Thời điểm lấy
 * từ {@code service.security.AuthenticationClock} (đồng hồ DB, cùng nguồn với {@code users.password_changed_at}), không
 * từ đồng hồ JVM.
 */
public final class SessionAuthenticationTime {

    /** Tên attribute trong session HTTP. */
    public static final String ATTRIBUTE = "foodsndrinks.authenticatedAt";

    private SessionAuthenticationTime() {
    }

    /** Ghi mốc xác thực vào session của request (tạo session nếu chưa có). */
    public static void stamp(HttpServletRequest request, Instant authenticatedAt) {
        request.getSession(true).setAttribute(ATTRIBUTE, authenticatedAt.toEpochMilli());
    }

    /** @return mốc xác thực của session; {@code null} nếu không có session, session đã bị huỷ hoặc chưa từng được ghi mốc */
    public static Instant of(HttpSession session) {
        if (session == null) {
            return null;
        }
        try {
            return session.getAttribute(ATTRIBUTE) instanceof Long millis ? Instant.ofEpochMilli(millis) : null;
        } catch (IllegalStateException invalidated) {
            return null;
        }
    }
}
