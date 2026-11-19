package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Prod không khởi động khi thiếu, để rỗng hoặc đặt sai định dạng {@code INSTANCE_ID}. */
class InstanceIdCheckTest {

    // Giống application-prod.yml: không có INSTANCE_ID thì instance-id rỗng (không lấy hostname)
    private final ApplicationContextRunner prod = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withUserConfiguration(InstanceIdCheck.class)
            .withPropertyValues(InstanceIdCheck.PROPERTY + "=${INSTANCE_ID:}");

    @Test
    void missingInstanceId_failsStartup() {
        prod.run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("INSTANCE_ID chưa được khai báo"));
    }

    @Test
    void blankInstanceId_failsStartup() {
        prod.withPropertyValues("INSTANCE_ID= ")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("INSTANCE_ID chưa được khai báo"));
    }

    @Test
    void unresolvedPlaceholder_failsStartup() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(InstanceIdCheck.class)
                .withPropertyValues(InstanceIdCheck.PROPERTY + "=${INSTANCE_ID}")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("INSTANCE_ID chưa được khai báo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "app/1", ".hidden", "..", "app 1", "app\n1",
            "a2345678901234567890123456789012345678901234567890123456789012345"}) // 65 ký tự
    void invalidInstanceId_failsStartup(String instanceId) {
        prod.withPropertyValues("INSTANCE_ID=" + instanceId)
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("INSTANCE_ID không hợp lệ"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"app-1", "web_02", "node.prod.3", "A",
            "a234567890123456789012345678901234567890123456789012345678901234"}) // 64 ký tự
    void validInstanceId_startsNormally(String instanceId) {
        prod.withPropertyValues("INSTANCE_ID=" + instanceId)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(InstanceIdCheck.class));
    }

    @Test
    void notActiveOutsideProd() {
        new ApplicationContextRunner()
                .withUserConfiguration(InstanceIdCheck.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InstanceIdCheck.class));
    }
}
