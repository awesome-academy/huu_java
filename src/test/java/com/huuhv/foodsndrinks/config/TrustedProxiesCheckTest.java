package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Prod không khởi động khi thiếu hoặc để rỗng {@code TRUSTED_PROXIES}. */
class TrustedProxiesCheckTest {

    private static final String INTERNAL_PROXIES = TrustedProxiesCheck.PROPERTY;

    private final ApplicationContextRunner prod = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withUserConfiguration(TrustedProxiesCheck.class)
            .withPropertyValues(INTERNAL_PROXIES + "=${TRUSTED_PROXIES}");

    @Test
    void binderKeepsUnresolvedPlaceholder_soTomcatAloneWouldNotFail() {
        // Lý do cần TrustedProxiesCheck: binder của Boot không báo lỗi khi thiếu biến
        MockEnvironment environment = new MockEnvironment().withProperty(INTERNAL_PROXIES, "${TRUSTED_PROXIES}");
        assertThat(Binder.get(environment).bind(INTERNAL_PROXIES, String.class).get())
                .isEqualTo("${TRUSTED_PROXIES}");
    }

    @Test
    void missingTrustedProxies_failsStartup() {
        prod.run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("TRUSTED_PROXIES chưa được khai báo"));
    }

    @Test
    void blankTrustedProxies_failsStartup() {
        prod.withPropertyValues("TRUSTED_PROXIES= ")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("TRUSTED_PROXIES chưa được khai báo"));
    }

    @Test
    void declaredTrustedProxies_startsNormally() {
        prod.withPropertyValues("TRUSTED_PROXIES=10.0.1.0/24")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TrustedProxiesCheck.class));
    }

    @Test
    void notActiveOutsideProd() {
        new ApplicationContextRunner()
                .withUserConfiguration(TrustedProxiesCheck.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TrustedProxiesCheck.class));
    }
}
