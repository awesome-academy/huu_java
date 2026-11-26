package com.huuhv.foodsndrinks.dto.response;

/**
 * {@code GET /csrf-token}: token CSRF (đã mask) của session hiện tại và tên header gửi kèm — trang đã mở lâu lấy lại
 * token khi session đổi (remember-me đăng nhập lại sau khi session hết hạn, đăng nhập lại ở tab khác).
 */
public record CsrfTokenResDto(String headerName, String token) {
}
