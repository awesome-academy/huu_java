package com.huuhv.foodsndrinks.config.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** {@code app.websocket.*}: mặc định, origin cùng quy tắc với CORS, chu kỳ/heartbeat sai thì không khởi động. */
class WebSocketPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaults_sameOriginOnly() {
        runner.run(context -> {
            WebSocketProperties properties = context.getBean(WebSocketProperties.class);
            assertThat(properties.allowedOrigins()).isEmpty();
            assertThat(properties.revalidateInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.heartbeat()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.maxSubscriptionsPerSession()).isEqualTo(5);
            assertThat(properties.maxConnectionsPerUser()).isEqualTo(10);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.websocket.max-subscriptions-per-session=0", "app.websocket.max-subscriptions-per-session=51",
            "app.websocket.max-connections-per-user=0", "app.websocket.max-connections-per-user=101"})
    void limitsOutOfRange_failStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void origins_areNormalized() {
        assertThat(new WebSocketProperties(List.of(" HTTPS://Shop.Example.com ", "", "https://shop.example.com",
                "http://localhost:8080"), Duration.ofMinutes(1), Duration.ofSeconds(10), 5, 10).allowedOrigins())
                .containsExactly("https://shop.example.com", "http://localhost:8080");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "null", "https://shop.example.com/", "http://shop.example.com",
            "shop.example.com"})
    void invalidOrigin_isRejected_withVariableName(String origin) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebSocketProperties(List.of(origin), Duration.ofMinutes(1), Duration.ofSeconds(10), 5, 10))
                .withMessageContaining("WEBSOCKET_ALLOWED_ORIGINS");
    }

    @Test
    void tooShortRevalidateInterval_failsStartup() {
        runner.withPropertyValues("app.websocket.revalidate-interval=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void tooLongHeartbeat_failsStartup() {
        runner.withPropertyValues("app.websocket.heartbeat=10m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebSocketProperties.class)
    static class Config {
    }
}
