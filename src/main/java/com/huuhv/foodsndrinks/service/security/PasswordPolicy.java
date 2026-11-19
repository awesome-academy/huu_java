package com.huuhv.foodsndrinks.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Chính sách mật khẩu theo OWASP/NIST — nguồn duy nhất cho đăng ký, đổi mật khẩu trong hồ sơ và admin đặt lại mật
 * khẩu (qua {@code @ValidPassword}): 8–64 ký tự, không giới hạn loại ký tự, và không nằm trong danh sách đã bị lộ.
 * <p>
 * Thêm giới hạn 72 byte UTF-8: BCrypt chỉ dùng 72 byte đầu và {@code BCryptPasswordEncoder} ném exception khi encode
 * chuỗi dài hơn — 64 ký tự có dấu (2–3 byte/ký tự) có thể vượt mức này.
 */
@Slf4j
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    static final int MAX_BYTES = 72;

    static final String LENGTH_MESSAGE = "Mật khẩu phải từ " + MIN_LENGTH + " đến " + MAX_LENGTH + " ký tự.";
    static final String BYTES_MESSAGE = "Mật khẩu quá dài (ký tự có dấu chiếm nhiều byte), vui lòng rút ngắn.";
    static final String COMPROMISED_MESSAGE =
            "Mật khẩu này đã xuất hiện trong các vụ lộ dữ liệu, vui lòng chọn mật khẩu khác.";

    /** Null khi tắt kiểm tra ({@code app.security.password.breach-check-enabled=false}). */
    private final CompromisedPasswordChecker compromisedPasswordChecker;

    public PasswordPolicy(ObjectProvider<CompromisedPasswordChecker> compromisedPasswordChecker) {
        this.compromisedPasswordChecker = compromisedPasswordChecker.getIfAvailable();
    }

    /**
     * @return thông báo lỗi (hiển thị cho người dùng) hoặc rỗng nếu hợp lệ. Null/chuỗi rỗng được coi là hợp lệ
     *         ("không đổi mật khẩu") — chỗ bắt buộc nhập thì khai báo thêm {@code @NotBlank}.
     */
    public Optional<String> violation(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return Optional.empty();
        }
        int length = rawPassword.codePointCount(0, rawPassword.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return Optional.of(LENGTH_MESSAGE);
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of(BYTES_MESSAGE);
        }
        if (isCompromised(rawPassword)) {
            return Optional.of(COMPROMISED_MESSAGE);
        }
        return Optional.empty();
    }

    /** Dịch vụ kiểm tra lỗi/timeout → cho qua (không chặn người dùng vì lỗi bên thứ ba) và ghi WARN. */
    private boolean isCompromised(String rawPassword) {
        if (compromisedPasswordChecker == null) {
            return false;
        }
        try {
            return compromisedPasswordChecker.check(rawPassword).isCompromised();
        } catch (RuntimeException ex) {
            log.warn("Compromised password check unavailable, skipping: {}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
