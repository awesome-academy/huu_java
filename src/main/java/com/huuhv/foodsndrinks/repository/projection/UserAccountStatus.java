package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.Role;

import java.time.Instant;

/** Trạng thái tài khoản dùng để kiểm tra session đang đăng nhập (chỉ 3 cột, không load cả entity {@code User}). */
public interface UserAccountStatus {

    Boolean getActive();

    Role getRole();

    /** Lần đổi mật khẩu gần nhất, {@code null} nếu chưa đổi — session xác thực trước mốc này phải bị huỷ. */
    Instant getPasswordChangedAt();
}
