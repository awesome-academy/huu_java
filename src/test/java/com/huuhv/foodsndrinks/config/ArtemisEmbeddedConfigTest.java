package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.service.messaging.Destinations;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.artemis.autoconfigure.ArtemisMode;
import org.springframework.boot.artemis.autoconfigure.ArtemisProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * File {@code artemis/broker.properties} của production và cơ chế nạp nó vào broker embedded. Sửa file đó hoặc đổi tên
 * destination ({@link Destinations}) thì chạy lại test này: broker riêng (compose/prod) gặp khoá sai chỉ bỏ qua lặng lẽ.
 */
class ArtemisEmbeddedConfigTest {

    private static final String PRODUCTION_FILE = "classpath:artemis/broker.properties";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void productionFile_configuresRetryAndDeadLetterForOrderPlaced() {
        ConfigurationImpl configuration = apply(PRODUCTION_FILE);

        assertThat(address(configuration, Destinations.ORDER_PLACED).getRoutingTypes())
                .containsExactly(RoutingType.MULTICAST);
        AddressSettings topic = configuration.getAddressSettings().get(Destinations.ORDER_PLACED);
        assertThat(topic.getMaxDeliveryAttempts()).isEqualTo(5);
        assertThat(topic.getRedeliveryDelay()).isEqualTo(2000);
        assertThat(topic.getRedeliveryMultiplier()).isEqualTo(2.0);
        assertThat(topic.getMaxRedeliveryDelay()).isEqualTo(30000);
        assertThat(topic.getDeadLetterAddress().toString()).isEqualTo(Destinations.ORDER_PLACED_DLQ);
    }

    @Test
    void productionFile_createsDeadLetterQueue_withItsOwnUnlimitedRetry() {
        ConfigurationImpl configuration = apply(PRODUCTION_FILE);

        // Dead-letter address không có queue → broker bỏ message
        CoreAddressConfiguration dlq = address(configuration, Destinations.ORDER_PLACED_DLQ);
        assertThat(dlq.getRoutingTypes()).containsExactly(RoutingType.ANYCAST);
        assertThat(dlq.getQueueConfigs()).singleElement().satisfies(queue -> {
            assertThat(queue.getName().toString()).isEqualTo(Destinations.ORDER_PLACED_DLQ);
            assertThat(queue.getAddress().toString()).isEqualTo(Destinations.ORDER_PLACED_DLQ);
            assertThat(queue.getRoutingType()).isEqualTo(RoutingType.ANYCAST);
        });
        AddressSettings dlqSettings = configuration.getAddressSettings().get(Destinations.ORDER_PLACED_DLQ);
        assertThat(dlqSettings.getMaxDeliveryAttempts()).isEqualTo(-1);
        assertThat(dlqSettings.getRedeliveryDelay()).isPositive();
    }

    @Test
    void laterFile_overridesKeysOfEarlierFile() {
        ConfigurationImpl configuration = apply(PRODUCTION_FILE, "classpath:artemis/broker-test.properties");

        AddressSettings topic = configuration.getAddressSettings().get(Destinations.ORDER_PLACED);
        assertThat(topic.getRedeliveryDelay()).isEqualTo(100);
        assertThat(topic.getMaxDeliveryAttempts()).isEqualTo(5);
        assertThat(topic.getDeadLetterAddress().toString()).isEqualTo(Destinations.ORDER_PLACED_DLQ);
    }

    @Test
    void unknownKey_failsStartup_insteadOfBeingIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("typo.properties");
        Files.writeString(file, "addressSettings.\"order.placed\".maxDeliveryAtempts=5\n");

        assertThatThrownBy(() -> ArtemisEmbeddedConfig.applyBrokerProperties(new ConfigurationImpl(),
                new FileSystemResource(file), file.toString(), jsonMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxDeliveryAtempts");
    }

    @Test
    void missingOrEmptyFile_failsStartup() {
        ByteArrayResource empty = new ByteArrayResource(new byte[0]);

        assertThatThrownBy(() -> apply("classpath:artemis/does-not-exist.properties"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> ArtemisEmbeddedConfig.applyBrokerProperties(new ConfigurationImpl(), empty,
                "empty", jsonMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no keys");
    }

    @Test
    void unrecognizedStatusFormat_failsClosed() {
        assertThatThrownBy(() -> ArtemisEmbeddedConfig.errors("{}", "broker.properties", jsonMapper))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ArtemisEmbeddedConfig.errors("not json", "broker.properties", jsonMapper))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ArtemisEmbeddedConfig.errors(
                "{\"properties\":{\"broker.properties\":{\"errors\":[]}}}", "broker.properties", jsonMapper)).isEmpty();
    }

    @Test
    void embeddedBrokerStarting_whileModeIsNative_failsStartup() {
        ArtemisProperties artemisProperties = new ArtemisProperties();
        artemisProperties.setMode(ArtemisMode.NATIVE);
        var customizer = new ArtemisEmbeddedConfig().brokerPropertiesCustomizer(artemisProperties,
                new JmsBrokerProperties(List.of(PRODUCTION_FILE)), new DefaultResourceLoader(), jsonMapper);

        assertThatThrownBy(() -> customizer.customize(new ConfigurationImpl()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.artemis.embedded.enabled=false");
    }

    private ConfigurationImpl apply(String... locations) {
        ConfigurationImpl configuration = new ConfigurationImpl();
        for (String location : locations) {
            ArtemisEmbeddedConfig.applyBrokerProperties(configuration,
                    new DefaultResourceLoader().getResource(location), location, jsonMapper);
        }
        return configuration;
    }

    private static CoreAddressConfiguration address(ConfigurationImpl configuration, String name) {
        return configuration.getAddressConfigurations().stream()
                .filter(address -> address.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No address configuration " + name));
    }
}
