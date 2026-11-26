package com.huuhv.foodsndrinks.controller;

import com.huuhv.foodsndrinks.dto.response.CsrfTokenResDto;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token CSRF của session hiện tại cho JS của trang đã mở lâu ({@code static/js/realtime.js} gọi trước mỗi lần kết nối
 * WebSocket lại). Token trong {@code <meta name="_csrf">} gắn với session lúc render trang: session đổi (hết hạn rồi
 * remember-me đăng nhập lại, đăng nhập lại ở tab khác) thì frame CONNECT mang token cũ bị từ chối mãi.
 * <p>
 * Chỉ người đã đăng nhập (chain web, {@code SecurityConfig}); chưa đăng nhập → chuyển hướng {@code /login} — JS coi là
 * session đã hết và dừng kết nối lại. Đọc được token chỉ từ cùng origin: chain web không mở CORS. Response không được
 * cache (header {@code Cache-Control} mặc định của Spring Security). Request {@code Accept: application/json} không bị
 * request cache lưu, nên đăng nhập xong không bị đưa về URL này.
 */
@RestController
public class CsrfTokenController {

    public static final String PATH = "/csrf-token";

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public CsrfTokenResDto csrfToken(CsrfToken csrfToken) {
        return new CsrfTokenResDto(csrfToken.getHeaderName(), csrfToken.getToken());
    }
}
