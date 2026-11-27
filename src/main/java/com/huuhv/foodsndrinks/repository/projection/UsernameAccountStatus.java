package com.huuhv.foodsndrinks.repository.projection;

/** {@link UserAccountStatus} kèm username — tra nhiều tài khoản trong một query (thu hồi kết nối WebSocket). */
public interface UsernameAccountStatus extends UserAccountStatus {

    String getUsername();
}
