package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.FoodsndrinksApplication;
import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.service.messaging.Destinations;
import com.huuhv.foodsndrinks.support.BrowserSession;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.artemis.autoconfigure.ArtemisProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai instance của app chạy cùng lúc như sau load balancer: instance A là context của test (broker Artemis embedded),
 * instance B là một {@code SpringApplication} thứ hai trong cùng JVM — cổng riêng, {@code app.instance-id} riêng, chung
 * MySQL Testcontainers và nối in-VM vào broker của A (broker dùng chung như prod). Simple broker STOMP nằm trong bộ nhớ
 * từng instance: sự kiện phát ở một instance phải tới admin đang nối vào instance kia qua topic JMS.
 */
class MultiInstanceRealtimeIntegrationTest extends AbstractRealtimeIntegrationTest {

    private static ConfigurableApplicationContext instanceB;

    @Autowired private DataSource dataSource;
    @Autowired private ArtemisProperties artemisProperties;

    @BeforeEach
    void startInstanceB() throws Exception {
        if (instanceB == null) {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            Map<String, Object> properties = new HashMap<>();
            properties.put("server.port", 0);
            properties.put("spring.profiles.active", "test");
            properties.put("spring.datasource.url", hikari.getJdbcUrl());
            properties.put("spring.datasource.username", hikari.getUsername());
            properties.put("spring.datasource.password", hikari.getPassword());
            // Schema đã do instance A migrate
            properties.put("spring.flyway.enabled", false);
            // Không chạy broker riêng: nối in-VM vào broker embedded của A
            properties.put("spring.artemis.mode", "embedded");
            properties.put("spring.artemis.embedded.enabled", false);
            properties.put("spring.artemis.embedded.server-id", artemisProperties.getEmbedded().getServerId());
            properties.put("app.instance-id", "instance-b");
            // Tham số dòng lệnh: thắng config/application.yml của test (properties() mặc định thì không)
            String[] args = properties.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new);
            instanceB = new SpringApplicationBuilder(FoodsndrinksApplication.class).run(args);
        }
        awaitRegistered(instanceB.getBean(JmsListenerEndpointRegistry.class));
    }

    @AfterAll
    static void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
            instanceB = null;
        }
    }

    @Test
    void orderPlacedOnOneInstance_reachesAdminsConnectedToEveryInstance() throws Exception {
        int portB = Integer.parseInt(instanceB.getEnvironment().getRequiredProperty("local.server.port"));
        User adminOnA = createUser(Role.ROLE_ADMIN);
        User adminOnB = createUser(Role.ROLE_ADMIN);
        BrowserSession.Stomp stompA = track(login(adminOnA).connect());
        BrowserSession.Stomp stompB = track(login(portB, adminOnB).connect());
        BlockingQueue<Map<String, Object>> eventsA = stompA.subscribe(StompDestinations.ADMIN_ORDERS);
        BlockingQueue<Map<String, Object>> eventsB = stompB.subscribe(StompDestinations.ADMIN_ORDERS);
        awaitSubscribed(userRegistry, adminOnA.getUsername(), StompDestinations.ADMIN_ORDERS);
        awaitSubscribed(instanceB.getBean(SimpUserRegistry.class), adminOnB.getUsername(), StompDestinations.ADMIN_ORDERS);
        // Mỗi instance chỉ biết client của chính nó
        assertThat(userRegistry.getUser(adminOnB.getUsername())).isNull();
        long orderId = newOrderId();

        // Đơn đặt ở instance B (outbox của B gửi lên topic)
        instanceB.getBean(JmsBrokerProperties.TOPIC_JMS_TEMPLATE, JmsTemplate.class)
                .convertAndSend(Destinations.ORDER_PLACED, orderPlaced(orderId));

        Map<String, Object> onA = eventsA.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        Map<String, Object> onB = eventsB.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(onA).as("admin on A").isNotNull();
        assertThat(onB).as("admin on B").isNotNull();
        assertThat(longValue(onA, "orderId")).isEqualTo(orderId);
        assertThat(longValue(onB, "orderId")).isEqualTo(orderId);
        // Đúng một bản mỗi admin (mỗi instance một subscription, không nhận trùng)
        assertThat(eventsA.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(eventsB.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }
}
