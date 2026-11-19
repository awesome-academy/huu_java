package com.huuhv.foodsndrinks.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Cấp và kiểm tra JWT của REST API: HS256 (cố định, không phụ thuộc độ dài key), claim {@code iss} = {@code jwt.issuer},
 * {@code aud} = {@code jwt.audience}. Token chỉ hợp lệ khi đúng chữ ký, còn hạn VÀ khớp cả iss lẫn aud — token do hệ
 * thống khác cấp (dù lỡ dùng chung secret) hoặc cấp cho audience khác bị từ chối. Không đặt dữ liệu nhạy cảm vào claims.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    /** JwtParser bất biến, thread-safe → dựng một lần thay vì mỗi request. */
    private final JwtParser parser;
    private final long expirationTime;
    private final String issuer;
    private final String audience;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expirationTime,
                   @Value("${jwt.issuer}") String issuer,
                   @Value("${jwt.audience}") String audience) {
        // Keys.hmacShaKeyFor ném WeakKeyException nếu secret < 256 bit → app không khởi động với secret yếu
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build();
        this.expirationTime = expirationTime;
        this.issuer = issuer;
        this.audience = audience;
    }

    /** Thời gian sống của token (milliseconds) */
    public long getExpirationTime() {
        return expirationTime;
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationTime))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @return subject (username) nếu token hợp lệ — chữ ký, hạn, iss, aud; rỗng nếu không. Không log token hay lý do
     *         chi tiết (message của jjwt có thể chứa giá trị claim).
     */
    public Optional<String> validUsername(String token) {
        try {
            return Optional.ofNullable(parser.parseSignedClaims(token).getPayload().getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
