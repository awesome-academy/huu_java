package com.huuhv.foodsndrinks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider HTTP Basic của chain SOAP ({@code /ws/**}). Chain stateless nên MỖI request là một lần xác thực; đi thẳng qua
 * {@link LoginAttemptAwareAuthenticationProvider} thì hệ thống đối tác gọi song song bị chặn oan: provider đó giữ chỗ
 * trong bộ đếm (IP, tên) trước khi so BCrypt và chỉ cho tối đa {@code max-failures} lần thử chạy cùng lúc, nên request
 * thứ 6 chạy đồng thời nhận 401 dù đúng mật khẩu — kèm khoảng 6 lần ghi {@code attempt_limits} + một lần BCrypt mỗi lời gọi.
 * <p>
 * Lớp này bọc provider đó ({@code delegate}, tạo với {@code resetCountersOnSuccess = false}):
 * <ul>
 *   <li><b>Gộp request trùng thông tin đăng nhập</b> (single-flight, trong một instance): request đầu tiên với một cặp
 *       (tên, mật khẩu) đi qua {@code delegate} — giới hạn đăng nhập sai, BCrypt; các request cùng cặp tới trong lúc đó
 *       CHỜ kết quả của nó thay vì giữ thêm chỗ. Cặp sai bị đếm đúng một lần và mọi request chờ nhận cùng lỗi.</li>
 *   <li><b>Nhớ lần xác thực đúng trong {@link #CACHE_TTL}</b>: request sau với cùng cặp không so BCrypt, không đụng bộ
 *       đếm. Vẫn nạp lại tài khoản từ DB ở MỌI request: tài khoản bị khoá/xoá bị từ chối ngay, role lấy theo DB, và
 *       mật khẩu đổi (hash trong DB khác hash lúc xác thực) thì kết quả cũ bị bỏ, request đi lại từ đầu qua
 *       {@code delegate}.</li>
 * </ul>
 * Cặp (tên, mật khẩu) khác nhau — đoán mật khẩu — không được gộp, nên giới hạn số lần thử đồng thời vẫn áp dụng cho kẻ dò
 * mật khẩu. Cache chỉ giữ HMAC-SHA256 của cặp (khoá ngẫu nhiên, sinh lúc khởi động, không rời bộ nhớ instance) và hash
 * BCrypt đã có trong DB — không giữ mật khẩu. Lần thất bại không bao giờ được nhớ.
 * <p>
 * Không phải bean ({@code AuthenticationProvider} duy nhất phải là provider của form/API) — tạo trong
 * {@code SecurityConfig#soapSecurityFilterChain}.
 */
public class SoapBasicAuthenticationProvider implements AuthenticationProvider {

    /** Thời gian nhớ một lần xác thực đúng. */
    static final Duration CACHE_TTL = Duration.ofMinutes(1);

    /** Số cặp thông tin đăng nhập nhớ tối đa (mỗi instance). */
    static final int MAX_ENTRIES = 10_000;

    /** Thời gian tối đa một request chờ request trùng thông tin đăng nhập đang xác thực. */
    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuthenticationProvider delegate;
    private final UserDetailsService userDetailsService;
    private final UserDetailsChecker accountStatusChecker = new AccountStatusUserDetailsChecker();
    private final SecretKeySpec keyHashSecret;
    /** HMAC của (tên, mật khẩu) → hash mật khẩu trong DB lúc xác thực đúng; future chưa xong = đang xác thực. */
    private final Cache<String, CompletableFuture<String>> verified;

    /**
     * @param delegate           {@link LoginAttemptAwareAuthenticationProvider} không xoá bộ đếm khi đúng
     * @param userDetailsService nạp lại tài khoản ở mỗi request dùng kết quả đã nhớ
     */
    public SoapBasicAuthenticationProvider(AuthenticationProvider delegate, UserDetailsService userDetailsService) {
        this(delegate, userDetailsService, Ticker.systemTicker());
    }

    SoapBasicAuthenticationProvider(AuthenticationProvider delegate, UserDetailsService userDetailsService,
                                    Ticker ticker) {
        this.delegate = delegate;
        this.userDetailsService = userDetailsService;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.keyHashSecret = new SecretKeySpec(secret, HMAC_ALGORITHM);
        this.verified = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken)
                || !(authentication.getCredentials() instanceof String password)) {
            return delegate.authenticate(authentication);
        }
        String loginId = authentication.getName();
        String key = keyOf(loginId, password);
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> existing = verified.asMap().putIfAbsent(key, pending);
        if (existing == null) {
            return verify(authentication, key, pending);
        }

        String verifiedPasswordHash = await(existing);
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(loginId);
        } catch (UsernameNotFoundException ex) {
            // Tài khoản bị xoá/đổi tên từ lần xác thực trước — báo như sai thông tin đăng nhập
            verified.asMap().remove(key, existing);
            throw new BadCredentialsException("Bad credentials");
        }
        if (!MessageDigest.isEqual(bytes(verifiedPasswordHash), bytes(user.getPassword()))) {
            // Mật khẩu đã đổi: kết quả cũ không còn giá trị, so lại từ đầu (qua giới hạn đăng nhập sai)
            verified.asMap().remove(key, existing);
            return delegate.authenticate(authentication);
        }
        accountStatusChecker.check(user);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    /** Request đầu tiên của cặp thông tin đăng nhập: xác thực đầy đủ rồi báo kết quả cho các request đang chờ. */
    private Authentication verify(Authentication authentication, String key, CompletableFuture<String> pending) {
        try {
            Authentication result = delegate.authenticate(authentication);
            // Lấy hash NGAY: ProviderManager xoá mật khẩu khỏi principal sau khi provider trả về
            if (result != null && result.getPrincipal() instanceof UserDetails user && user.getPassword() != null) {
                pending.complete(user.getPassword());
            } else {
                verified.asMap().remove(key, pending);
                pending.completeExceptionally(new BadCredentialsException("Bad credentials"));
            }
            return result;
        } catch (RuntimeException ex) {
            verified.asMap().remove(key, pending);
            pending.completeExceptionally(ex);
            throw ex;
        }
    }

    private static String await(CompletableFuture<String> pending) {
        try {
            return pending.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            // Cùng thông tin đăng nhập với request đang chạy → cùng kết quả (sai mật khẩu, bị khoá, bị giới hạn...)
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AuthenticationServiceException("Authentication failed", ex.getCause());
        } catch (TimeoutException ex) {
            throw new AuthenticationServiceException("Timed out waiting for a concurrent authentication");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthenticationServiceException("Interrupted while waiting for a concurrent authentication");
        }
    }

    private String keyOf(String loginId, String password) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keyHashSecret);
            mac.update(bytes(loginId));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(bytes(password)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is not available", ex);
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
