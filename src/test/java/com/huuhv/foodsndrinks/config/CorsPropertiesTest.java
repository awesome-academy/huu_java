package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CorsPropertiesTest {

    @Test
    void blankEntries_areDropped_soEmptyEnvVarMeansSameOriginOnly() {
        assertThat(new CorsProperties(List.of("", "  ")).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(List.of(" https://a.example.com ")).allowedOrigins())
                .containsExactly("https://a.example.com");
    }

    @Test
    void wildcard_isRejectedAtStartup() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CorsProperties(List.of("*")));
        assertThatIllegalArgumentException().isThrownBy(() -> new CorsProperties(List.of("https://*.example.com")));
    }
}
