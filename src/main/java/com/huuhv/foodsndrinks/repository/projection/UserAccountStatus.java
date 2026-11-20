package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.Role;

/** Trạng thái tài khoản dùng để kiểm tra session đang đăng nhập (chỉ 2 cột, không load cả entity {@code User}). */
public interface UserAccountStatus {

    Boolean getActive();

    Role getRole();
}
