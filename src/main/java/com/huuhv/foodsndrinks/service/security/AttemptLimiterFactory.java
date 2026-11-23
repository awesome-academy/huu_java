package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.config.properties.AttemptLimitProperties;
import com.huuhv.foodsndrinks.repository.AttemptLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.InstantSource;

/** Tạo {@link AttemptLimiter} dùng chung repository, khoá băm key ({@link AttemptLimitProperties}) và đồng hồ. */
@Component
public class AttemptLimiterFactory {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AttemptLimitRepository repository;
    private final SecretKeySpec hashKey;
    private final InstantSource clock;

    @Autowired
    public AttemptLimiterFactory(AttemptLimitRepository repository, AttemptLimitProperties properties) {
        this(repository, properties.keySecret(), InstantSource.system());
    }

    /** Cho test: repository giả và đồng hồ điều khiển được. */
    public AttemptLimiterFactory(AttemptLimitRepository repository, String keySecret, InstantSource clock) {
        this.repository = repository;
        this.hashKey = new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.clock = clock;
        hash(""); // thuật toán/khoá không dùng được thì app không khởi động
    }

    AttemptLimiter create(String name, int max, Duration window, Duration lockDuration) {
        return new AttemptLimiter(name, max, window, lockDuration, repository, this::hash, clock);
    }

    private byte[] hash(String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM); // Mac không thread-safe: tạo mới mỗi lần (rẻ so với BCrypt)
            mac.init(hashKey);
            return mac.doFinal(key.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is not available", e);
        }
    }
}
