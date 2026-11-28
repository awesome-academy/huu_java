package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionBudgetCheckTest {

    private final AsyncProperties defaults = new AsyncProperties(
            new AsyncProperties.Pool(2, 4, 100),
            new AsyncProperties.DashboardPool(4, 4, 50, Duration.ofSeconds(10)),
            new AsyncProperties.OutboxPool(1, 2, 100));

    @Test
    void defaultConfiguration_fitsInHalfOfTheConfiguredHikariPool() throws Exception {
        // Chính application.yml của app + 7 listener JMS một thread (2 subscription thông báo + DLQ đơn hàng + 2 WebSocket:
        // đơn mới, đổi trạng thái + import Excel + DLQ import Excel)
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        assertThatNoException().isThrownBy(() -> check(environment, 1, 1, 1, 1, 1, 1, 1).verify());
        // Thêm một listener nữa mà không tăng pool Hikari → app không khởi động
        assertThatIllegalStateException().isThrownBy(() -> check(environment, 1, 1, 1, 1, 1, 1, 1, 1).verify());
    }

    @Test
    void tooManyBackgroundConnections_failsStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(AsyncConfig.HIKARI_MAX_POOL_SIZE, "10")
                .withProperty(ConnectionBudgetCheck.SCHEDULING_POOL_SIZE, "2");

        // dashboard 4 + outbox 2 + JMS 3 + sched 2 = 11 > 10 / 2
        assertThatIllegalStateException().isThrownBy(() -> check(environment, 1, 1, 1).verify())
                .withMessageContaining("JMS listeners 3").withMessageContaining("increase the Hikari pool");
    }

    @Test
    void listenerConcurrency_countsAsConnections() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(AsyncConfig.HIKARI_MAX_POOL_SIZE, "24")
                .withProperty(ConnectionBudgetCheck.SCHEDULING_POOL_SIZE, "2");

        // 4 + 2 + (3 + 3 + 3) + 2 = 17 > 12: đặt lại concurrency 1–3 cho listener thông báo là vượt ngân sách
        assertThatIllegalStateException().isThrownBy(() -> check(environment, 3, 3, 3).verify());
    }

    @SuppressWarnings("unchecked")
    private ConnectionBudgetCheck check(MockEnvironment environment, int... listenerMaxConsumers) {
        List<MessageListenerContainer> containers = Arrays.stream(listenerMaxConsumers)
                .mapToObj(max -> {
                    DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
                    container.setMaxConcurrentConsumers(max);
                    return (MessageListenerContainer) container;
                })
                .toList();
        JmsListenerEndpointRegistry registry = mock(JmsListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(containers);
        ObjectProvider<JmsListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(Stream.of(registry));
        return new ConnectionBudgetCheck(defaults, environment, provider);
    }
}
