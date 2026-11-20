package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "NULL",                          // origin của iframe sandbox / file:// — ai cũng tạo được
            "https://app.example.com/",              // dấu '/' cuối: không bao giờ khớp header Origin
            "https://app.example.com/path",
            "https://app.example.com?x=1",
            "https://user@app.example.com",
            "http://app.example.com",                // http chỉ cho localhost
            "app.example.com",                       // thiếu scheme
            "ftp://app.example.com",
            "https://"})
    void invalidOrigins_areRejectedAtStartup(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> new CorsProperties(List.of(origin)))
                .withMessageContaining("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void validOrigins_areNormalizedToLowercase_andDeduplicated() {
        assertThat(new CorsProperties(List.of("HTTPS://App.Example.com:8443", "https://app.example.com:8443",
                "http://localhost:5173", "http://127.0.0.1:3000", "http://[::1]:8080")).allowedOrigins())
                .containsExactly("https://app.example.com:8443", "http://localhost:5173", "http://127.0.0.1:3000",
                        "http://[::1]:8080");
    }
}
