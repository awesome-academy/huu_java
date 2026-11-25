package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bộ đếm chống brute-force/giới hạn tần suất dùng chung trong bảng {@code attempt_limits}.
 *
 * @param keySecret khoá HMAC-SHA256 băm key trước khi lưu xuống DB (key chứa IP và chuỗi người dùng gõ vào ô tên đăng
 *                  nhập — có thể là mật khẩu gõ nhầm ô; băm không khoá thì dò ngược được vì không gian IP/username
 *                  nhỏ). ≥ 32 ký tự. Mọi instance phải dùng chung một giá trị; đổi giá trị thì mọi bộ đếm/khoá về 0
 */
@Validated
@ConfigurationProperties("app.security.attempt-limits")
public record AttemptLimitProperties(@NotBlank @Size(min = 32) String keySecret) {

    @Override
    public String toString() {
        return "AttemptLimitProperties[keySecret=***]";
    }
}
