package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.TestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cờ của cookie session ({@code server.servlet.session.cookie.*}) do Tomcat ghi — MockMvc không áp dụng nên dùng server
 * thật. Trang /login lưu CSRF token vào session nên luôn tạo cookie JSESSIONID.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieTest {

    @LocalServerPort
    private int port;

    @Test
    void sessionCookie_isHttpOnlyAndSameSiteLax_andNotSecureOverPlainHttpInDev() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).GET().build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            assertThat(response.headers().allValues("Set-Cookie"))
                    .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                    .singleElement().asString()
                    .contains("HttpOnly")
                    .containsIgnoringCase("SameSite=Lax")
                    .doesNotContain("Secure");
        }
    }
}
