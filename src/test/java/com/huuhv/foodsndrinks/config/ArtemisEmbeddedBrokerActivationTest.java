package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 4 tạo broker embedded theo {@code spring.artemis.embedded.enabled}, không xét {@code mode}: kiểm tra trên
 * chính các file yml của app rằng prod/compose (broker riêng) tắt broker embedded, còn dev mặc định dùng nó. Chỉ nạp
 * file yml (không có biến môi trường) nên placeholder lấy giá trị mặc định.
 */
class ArtemisEmbeddedBrokerActivationTest {

    @Test
    void defaultConfiguration_usesEmbeddedBroker() throws IOException {
        PropertySourcesPropertyResolver resolver = resolver("application.yml");

        assertThat(resolver.getProperty("spring.artemis.mode")).isEqualTo("embedded");
        assertThat(resolver.getProperty("spring.artemis.embedded.enabled")).isEqualTo("true");
        assertThat(resolver.getProperty("spring.jms.listener.session.transacted")).isEqualTo("true");
    }

    @Test
    void prodProfile_usesNativeBroker_andDisablesEmbeddedBroker() throws IOException {
        PropertySourcesPropertyResolver resolver = resolver("application-prod.yml", "application.yml");

        assertThat(resolver.getProperty("spring.artemis.mode")).isEqualTo("native");
        assertThat(resolver.getProperty("spring.artemis.embedded.enabled")).isEqualTo("false");
    }

    @Test
    void composeAppContainer_nativeMode_disablesEmbeddedBroker() throws IOException {
        // Biến mà docker-compose.yaml đặt cho service app
        PropertySourcesPropertyResolver resolver = resolver(
                new MapPropertySource("compose", Map.of("ARTEMIS_MODE", "native", "ARTEMIS_EMBEDDED_ENABLED", "false")),
                "application.yml");

        assertThat(resolver.getProperty("spring.artemis.mode")).isEqualTo("native");
        assertThat(resolver.getProperty("spring.artemis.embedded.enabled")).isEqualTo("false");
    }

    @Test
    void brokerUrl_hasConnectAndCallTimeouts() throws IOException {
        for (PropertySourcesPropertyResolver resolver : List.of(resolver("application.yml"),
                resolver(new MapPropertySource("env", Map.of("ARTEMIS_HOST", "broker", "ARTEMIS_PORT", "61616",
                        "ARTEMIS_USER", "u", "ARTEMIS_PASSWORD", "p")), "application-prod.yml", "application.yml"))) {
            assertThat(resolver.getProperty("spring.artemis.broker-url"))
                    .contains("connectTimeoutMillis=").contains("callTimeout=");
        }
    }

    private static PropertySourcesPropertyResolver resolver(String... files) throws IOException {
        return resolver(null, files);
    }

    /** Nguồn đứng trước thắng nguồn sau, như thứ tự ưu tiên của Spring Boot. */
    private static PropertySourcesPropertyResolver resolver(PropertySource<?> first, String... files) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        if (first != null) {
            sources.addLast(first);
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : files) {
            loader.load(file, new ClassPathResource(file)).forEach(sources::addLast);
        }
        return new PropertySourcesPropertyResolver(sources);
    }
}
