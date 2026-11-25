package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Chống brute-force đăng nhập (form + API) và dò mật khẩu hiện tại khi đổi mật khẩu. Mọi bộ đếm dùng cửa sổ cố định
 * {@code window} (tính từ lần thử đầu tiên); vượt ngưỡng thì khoá phạm vi đó trong {@code lockDuration}.
 *
 * @param maxFailures           số lần sai tối đa của một cặp (IP, tên đăng nhập) — người dùng thật gõ sai chỉ tự khoá
 *                              mình trên máy mình. Cũng là ngưỡng sai mật khẩu hiện tại khi đổi mật khẩu
 * @param maxFailuresPerIp      số lần sai tối đa từ một IP (IPv6: mạng /64) với mọi tên đăng nhập — chặn password
 *                              spraying (thử một mật khẩu trên rất nhiều tài khoản)
 * @param maxFailuresPerAccount số lần sai tối đa của một tài khoản từ mọi IP — chặn tấn công phân tán; đặt cao hơn hẳn
 *                              {@code maxFailures} để muốn khoá tài khoản người khác phải có nhiều IP
 * @param window                ≥ 1 giây: cửa sổ 0 làm bộ đếm hết hạn ngay sau mỗi lần thử, tức là tắt hẳn chống
 *                              brute-force — app không khởi động thay vì lặng lẽ tắt
 * @param lockDuration          ≥ 1 giây, cùng lý do
 */
@Validated
@ConfigurationProperties("app.security.login-attempts")
public record LoginAttemptProperties(
        @DefaultValue("5") @Min(1) int maxFailures,
        @DefaultValue("20") @Min(1) int maxFailuresPerIp,
        @DefaultValue("50") @Min(1) int maxFailuresPerAccount,
        @DefaultValue("15m") @DurationMin(seconds = 1) Duration window,
        @DefaultValue("15m") @DurationMin(seconds = 1) Duration lockDuration) {
}
