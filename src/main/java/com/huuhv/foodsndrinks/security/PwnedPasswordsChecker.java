package com.huuhv.foodsndrinks.security;

import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Kiểm tra mật khẩu có trong danh sách bị lộ của Pwned Passwords (k-anonymity: chỉ gửi 5 ký tự đầu của SHA-1, không
 * gửi mật khẩu hay hash đầy đủ; bật {@code Add-Padding} để độ dài response không tiết lộ prefix).
 * <p>
 * Không dùng {@code HaveIBeenPwnedRestApiPasswordChecker} của Spring Security vì nó tự nuốt lỗi mạng (ghi ERROR kèm
 * stack trace và coi như không bị lộ) — người gọi không biết lần kiểm tra đã thất bại. Class này ném
 * {@code RestClientException} để {@code PasswordPolicy} quyết định cho qua và ghi WARN.
 */
public class PwnedPasswordsChecker implements CompromisedPasswordChecker {

    static final String RANGE_URL = "https://api.pwnedpasswords.com/range/{prefix}";
    private static final int PREFIX_LENGTH = 5;

    private final RestClient restClient;

    public PwnedPasswordsChecker(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CompromisedPasswordDecision check(String password) {
        if (password == null) {
            return new CompromisedPasswordDecision(false);
        }
        String hash = HexFormat.of().withUpperCase().formatHex(sha1(password));
        String suffix = hash.substring(PREFIX_LENGTH) + ":";
        String body = restClient.get()
                .uri(RANGE_URL, hash.substring(0, PREFIX_LENGTH))
                .header("Add-Padding", "true")
                .retrieve()
                .body(String.class);
        // Mỗi dòng "SUFFIX:COUNT"; dòng padding có COUNT = 0, không phải mật khẩu bị lộ
        boolean leaked = body != null && body.lines()
                .anyMatch(line -> line.startsWith(suffix) && !line.substring(suffix.length()).strip().equals("0"));
        return new CompromisedPasswordDecision(leaked);
    }

    private static byte[] sha1(String password) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is not available", ex);
        }
    }
}
