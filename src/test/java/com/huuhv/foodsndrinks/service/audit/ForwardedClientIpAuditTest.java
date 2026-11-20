package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.TestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy sau load balancer: {@code source.ip} của audit log phải là IP client thật, lấy từ {@code X-Forwarded-For}.
 * <p>
 * Dùng Tomcat thật (cổng ngẫu nhiên, vì {@code RemoteIpValve} không chạy trong MockMvc) với đúng các thuộc tính
 * {@code server.forward-headers-strategy} / {@code server.tomcat.remoteip.*} đọc từ {@code application-prod.yml}.
 * Prod không có default cho {@code internal-proxies} (bắt buộc đặt {@code TRUSTED_PROXIES}), nên test khai báo
 * {@value #LOAD_BALANCER} — request từ 127.0.0.1 đóng vai load balancer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedClientIpAuditTest {

    private static final String LOAD_BALANCER = "127.0.0.0/8, ::1/128";
    private static final String INTERNAL_PROXIES = "server.tomcat.remoteip.internal-proxies";
    private static final List<String> PROD_FORWARDING_PROPERTIES = List.of(
            "server.forward-headers-strategy",
            "server.tomcat.remoteip.remote-ip-header",
            "server.tomcat.remoteip.protocol-header");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void prodForwardingProperties(DynamicPropertyRegistry registry) {
        PropertySource<?> prod = loadProdYaml();
        for (String name : PROD_FORWARDING_PROPERTIES) {
            Object value = prod.getProperty(name);
            assertThat(value).as("%s trong application-prod.yml", name).isNotNull();
            registry.add(name, value::toString);
        }
        assertThat(prod.getProperty(INTERNAL_PROXIES)).as("prod không được có default cho TRUSTED_PROXIES")
                .hasToString("${TRUSTED_PROXIES}");
        registry.add(INTERNAL_PROXIES, () -> LOAD_BALANCER);
    }

    private static PropertySource<?> loadProdYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application-prod", new ClassPathResource("application-prod.yml")).getFirst();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void auditSourceIp_isRealClientIp_andSpoofedLeftmostEntryIsIgnored() throws Exception {
        // Client tự chèn 198.51.100.7; load balancer nối thêm IP thật của client (203.0.113.9) vào cuối header
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "198.51.100.7, 203.0.113.9")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"no-such-user\",\"password\":\"wrong-pass\"}"))
                .build();

        try (AuditLogTestSupport audit = new AuditLogTestSupport(); HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(audit.lines()).singleElement().asString()
                    .contains("event.action=LOGIN_FAILURE")
                    .endsWith(" source.ip=203.0.113.9")
                    .doesNotContain("198.51.100.7");
        }
    }
}
