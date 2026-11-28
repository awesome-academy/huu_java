package com.huuhv.foodsndrinks.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link SoapProperties}: URL dịch vụ tỷ giá chỉ đến từ cấu hình và chỉ nhận http/https (app không khởi động với giá trị
 * khác); trống = tắt tính năng; mật khẩu không bao giờ nằm trong {@code toString()}.
 */
class SoapPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SoapProperties.class)
    static class Config {
    }

    private static SoapProperties.ExchangeRate exchangeRate(String url, String username, String password) {
        return new SoapProperties.ExchangeRate(url, username, password, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofMinutes(10), Duration.ofMinutes(1), new SoapProperties.MockEndpoint(false));
    }

    @Test
    void defaults_keepTheExchangeRateFeatureAndTheMockEndpointOff() {
        runner.run(context -> {
            SoapProperties properties = context.getBean(SoapProperties.class);

            assertThat(properties.maxRequestSize()).isEqualTo(DataSize.ofKilobytes(64));
            assertThat(properties.exchangeRate().hasUrl()).isFalse();
            assertThat(properties.exchangeRate().hasCredentials()).isFalse();
            assertThat(properties.exchangeRate().mockEndpoint().enabled()).isFalse();
            // Tiêu chí của issue: connect/read timeout 3 giây, cache ngắn hạn
            assertThat(properties.exchangeRate().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.exchangeRate().readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.exchangeRate().cacheTtl()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    void urlAndCredentials_comeFromConfiguration() {
        runner.withPropertyValues(
                "app.soap.exchange-rate.url= https://rates.example.com/soap ",
                "app.soap.exchange-rate.username=svc",
                "app.soap.exchange-rate.password=s3cret",
                "app.soap.exchange-rate.mock-endpoint.enabled=true").run(context -> {
            SoapProperties.ExchangeRate exchangeRate = context.getBean(SoapProperties.class).exchangeRate();

            assertThat(exchangeRate.url()).isEqualTo("https://rates.example.com/soap");
            assertThat(exchangeRate.hasUrl()).isTrue();
            assertThat(exchangeRate.hasCredentials()).isTrue();
            assertThat(exchangeRate.password()).isEqualTo("s3cret");
            assertThat(exchangeRate.mockEndpoint().enabled()).isTrue();
        });
    }

    @Test
    void onlyHttpAndHttpsUrlsWithAHost_areAccepted_otherwiseTheAppDoesNotStart() {
        for (String ok : List.of("http://localhost:8080/ws", "https://rates.example.com/soap", "HTTPS://RATES.EXAMPLE.COM")) {
            assertThat(exchangeRate(ok, "", "").hasUrl()).as(ok).isTrue();
        }
        for (String bad : List.of("file:///etc/passwd", "ftp://rates.example.com/ws", "jar:http://x/y!/z", "gopher://x",
                "rates.example.com/ws", "http:///ws", "http://", "not a url", "javascript:alert(1)")) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> exchangeRate(bad, "", ""))
                    .withMessageContaining("SOAP_EXCHANGE_RATE_URL");
        }
        runner.withPropertyValues("app.soap.exchange-rate.url=file:///etc/passwd")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void urlWithEmbeddedCredentials_isRejected_withoutEchoingThem() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> exchangeRate("https://svc:hunter2@rates.example.com/ws", "", ""))
                .withMessageContaining("user-info")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("hunter2", "rates.example.com"));
    }

    @Test
    void toString_masksThePassword() {
        SoapProperties properties = new SoapProperties(DataSize.ofKilobytes(64),
                exchangeRate("https://rates.example.com/ws", "svc", "hunter2"));

        assertThat(properties.toString()).contains("username=svc", "password=***").doesNotContain("hunter2");
        assertThat(exchangeRate("", "", "").toString()).contains("password=,");
    }

    @Test
    void timeoutsAndTtl_haveSaneBounds() {
        runner.withPropertyValues("app.soap.exchange-rate.read-timeout=5m")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.soap.exchange-rate.cache-ttl=0s")
                .run(context -> assertThat(context).hasFailed());
    }
}
