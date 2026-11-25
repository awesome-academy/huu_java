package com.huuhv.foodsndrinks.config.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Cấu hình làm tắt/yếu chống brute-force thì app không khởi động thay vì lặng lẽ chạy. */
class AttemptLimitPropertiesValidationTest {

    @EnableConfigurationProperties({LoginAttemptProperties.class, RegistrationRateLimitProperties.class,
            AttemptLimitProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues("app.security.attempt-limits.key-secret=" + "k".repeat(32));

    @Test
    void defaults_bindAndStart() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LoginAttemptProperties login = context.getBean(LoginAttemptProperties.class);
            assertThat(login.window()).isEqualTo(Duration.ofMinutes(15));
            assertThat(login.lockDuration()).isEqualTo(Duration.ofMinutes(15));
            assertThat(context.getBean(RegistrationRateLimitProperties.class).window()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void oneSecondWindow_isTheMinimumAccepted() {
        runner.withPropertyValues("app.security.login-attempts.window=1s", "app.security.login-attempts.lock-duration=1s",
                        "app.security.registration.window=1s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.security.login-attempts.window=0s",
            "app.security.login-attempts.window=500ms",
            "app.security.login-attempts.lock-duration=0s",
            "app.security.registration.window=0s",
            "app.security.attempt-limits.key-secret=too-short",
            "app.security.attempt-limits.key-secret="})
    void settingThatDisablesOrWeakensTheLimiter_failsStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
