package com.huuhv.foodsndrinks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
    /**
     * Mỗi prefix ~1.000 suffix (~60KB) → tối đa ~15MB. Chỉ cache danh sách công khai của HIBP theo prefix (không cache
     * hash hay kết quả theo mật khẩu), nên spam cùng một mật khẩu không gọi ra ngoài lặp lại.
     */
    static final long MAX_CACHED_PREFIXES = 256;
    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RestClient restClient;
    /** prefix → các suffix bị lộ (COUNT > 0). Lỗi mạng không được cache (exception đi thẳng ra ngoài). */
    private final Cache<String, Set<String>> leakedSuffixesByPrefix = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_PREFIXES)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public PwnedPasswordsChecker(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CompromisedPasswordDecision check(String password) {
        if (password == null) {
            return new CompromisedPasswordDecision(false);
        }
        String hash = HexFormat.of().withUpperCase().formatHex(sha1(password));
        Set<String> leaked = leakedSuffixesByPrefix.get(hash.substring(0, PREFIX_LENGTH), this::fetchLeakedSuffixes);
        return new CompromisedPasswordDecision(leaked.contains(hash.substring(PREFIX_LENGTH)));
    }

    private Set<String> fetchLeakedSuffixes(String prefix) {
        String body = restClient.get()
                .uri(RANGE_URL, prefix)
                .header("Add-Padding", "true")
                .retrieve()
                .body(String.class);
        if (body == null) {
            return Set.of();
        }
        // Mỗi dòng "SUFFIX:COUNT"; dòng padding có COUNT = 0, không phải mật khẩu bị lộ
        return body.lines()
                .map(line -> line.split(":", 2))
                .filter(parts -> parts.length == 2 && !parts[1].strip().equals("0"))
                .map(parts -> parts[0].strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static byte[] sha1(String password) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is not available", ex);
        }
    }
}
