package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Header bảo mật có trên mọi chain; thư viện front-end tự host qua WebJars truy cập được khi chưa đăng nhập. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersTest {

    @Autowired private MockMvc mockMvc;

    private static ResultMatcher[] commonSecurityHeaders() {
        return new ResultMatcher[] {
                header().string("Referrer-Policy", "strict-origin-when-cross-origin"),
                header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"),
                header().string("X-Content-Type-Options", "nosniff"),
                header().string("X-Frame-Options", "DENY")};
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/api/v1/products", "/actuator/health"})
    void everyChain_sendsSecurityHeaders(String path) throws Exception {
        mockMvc.perform(get(path)).andExpectAll(commonSecurityHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/admin", "/no-such-page"})
    void webChain_sendsStrictContentSecurityPolicy(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    @Test
    void contentSecurityPolicy_neverAllowsInlineOrEvalScripts() {
        String scriptSrc = SecurityConfig.CONTENT_SECURITY_POLICY.split("script-src")[1].split(";")[0];

        assertThat(scriptSrc)
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval")
                .doesNotContain("http");
    }

    @Test
    void apiChain_hasNoCsp_soSwaggerUiKeepsWorking() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    @Test
    void hsts_isOnlySentOverHttps() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mockMvc.perform(get("/login").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security", containsString("includeSubDomains")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/webjars/bootstrap/css/bootstrap.min.css",
            "/webjars/bootstrap/js/bootstrap.bundle.min.js",
            "/webjars/font-awesome/css/all.min.css",
            "/webjars/font-awesome/webfonts/fa-solid-900.woff2",
            "/webjars/chart.js/dist/chart.umd.js"})
    void webjars_areServedWithoutLoginAndWithoutVersionInUrl(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpectAll(commonSecurityHeaders());
    }
}
