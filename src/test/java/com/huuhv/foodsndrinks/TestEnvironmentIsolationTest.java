package com.huuhv.foodsndrinks;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test không dùng cấu hình hay secret của dev. Nếu profile {@code dev} bật,
 * {@code application-dev.yml} thắng {@code src/test/resources/config/application.yml}, và test sẽ lấy datasource,
 * {@code JWT_SECRET} và {@code REMEMBER_ME_KEY} từ biến môi trường / {@code .env}.
 */
@IntegrationTest
class TestEnvironmentIsolationTest {

    @Autowired
    private Environment environment;

    @Test
    void onlyTestProfileIsActive() {
        assertThat(environment.getActiveProfiles()).contains("test").doesNotContain("dev", "prod");
    }

    @Test
    void datasourceProperty_pointsToInvalidHost_soOnlyServiceConnectionCanConnect() {
        assertThat(environment.getProperty("spring.datasource.url")).contains(".invalid");
        assertThat(environment.getProperty("spring.datasource.username")).isEmpty();
    }

    @Test
    void jms_usesEmbeddedBroker_notTheComposeBroker() {
        assertThat(environment.getProperty("spring.artemis.mode")).isEqualTo("embedded");
        assertThat(environment.getProperty("spring.artemis.embedded.enabled")).isEqualTo("true");
    }

    @Test
    void signingKeys_areRandomPerRun_notTakenFromEnv() {
        // getProperty("JWT_SECRET") gồm cả biến môi trường lẫn .env (spring.config.import)
        assertThat(environment.getProperty("jwt.secret"))
                .isNotBlank()
                .isNotEqualTo(environment.getProperty("JWT_SECRET"));
        assertThat(environment.getProperty("app.security.remember-me-key"))
                .isNotBlank()
                .isNotEqualTo(environment.getProperty("REMEMBER_ME_KEY"));
        assertThat(environment.getProperty("app.security.attempt-limits.key-secret"))
                .isNotBlank()
                .isNotEqualTo(environment.getProperty("ATTEMPT_LIMITS_KEY_SECRET"));
    }
}
