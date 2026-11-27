package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Prod không khởi động khi chưa khai báo origin được mở WebSocket (sau load balancer không dựa vào Host). */
class WebSocketOriginCheckTest {

    // Giống application-prod.yml
    private final ApplicationContextRunner prod = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withUserConfiguration(WebSocketOriginCheck.class)
            .withPropertyValues(WebSocketOriginCheck.PROPERTY + "=${WEBSOCKET_ALLOWED_ORIGINS:}");

    @Test
    void missingOrigins_failsStartup() {
        prod.run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("WEBSOCKET_ALLOWED_ORIGINS chưa được khai báo"));
    }

    @Test
    void blankOrigins_failsStartup() {
        prod.withPropertyValues("WEBSOCKET_ALLOWED_ORIGINS= , ")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("WEBSOCKET_ALLOWED_ORIGINS chưa được khai báo"));
    }

    @Test
    void declaredOrigin_starts() {
        prod.withPropertyValues("WEBSOCKET_ALLOWED_ORIGINS=https://shop.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void otherProfiles_doNotRequireOrigins() {
        new ApplicationContextRunner()
                .withUserConfiguration(WebSocketOriginCheck.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WebSocketOriginCheck.class));
    }
}
