package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.security.PwnedPasswordsChecker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration // Include all config of system
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Client gửi webhook Slack, gọi trên thread listener JMS: không có timeout thì webhook treo giữ thread (và connection
     * DB của {@code NotificationLogService}) vô thời hạn. Timeout → lỗi → broker giao lại message sau.
     */
    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Kiểm tra mật khẩu bị lộ khi đặt mật khẩu mới ({@code PasswordPolicy}). Timeout ngắn vì nằm trên luồng submit form;
     * lỗi/timeout thì {@code PasswordPolicy} cho qua. Tắt bằng {@code PASSWORD_BREACH_CHECK_ENABLED=false} (test luôn tắt).
     * Chỉ dùng khi đặt mật khẩu — không gắn vào provider đăng nhập.
     */
    @Bean
    @ConditionalOnProperty(name = "app.security.password.breach-check-enabled", havingValue = "true", matchIfMissing = true)
    public CompromisedPasswordChecker compromisedPasswordChecker() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        return new PwnedPasswordsChecker(RestClient.builder().requestFactory(requestFactory).build());
    }
}
