package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.FoodsndrinksApplication;
import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
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
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai instance của app chạy cùng lúc như sau load balancer: instance A là context của test (broker Artemis embedded),
 * instance B là một {@code SpringApplication} thứ hai trong cùng JVM — cổng riêng, {@code app.instance-id} riêng, chung
 * MySQL Testcontainers và nối in-VM vào broker của A (broker dùng chung như prod). Simple broker STOMP nằm trong bộ nhớ
 * từng instance: sự kiện phát ở một instance phải tới admin đang nối vào instance kia qua topic JMS.
 * <p>
 * Session HTTP nằm trong kho dùng chung (Spring Session JDBC, issue #44): cùng một "trình duyệt" (cookie) gửi request lần
 * lượt tới A và B như sau một load balancer KHÔNG sticky — đăng nhập, CSRF, logout, thu hồi session và handshake WebSocket
 * đều phải đúng ở instance không tạo ra session.
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

    private static int portB() {
        return Integer.parseInt(instanceB.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private static boolean redirectsToLogin(HttpResponse<String> response) {
        return response.statusCode() == 302 && response.headers().firstValue("Location").orElse("").endsWith("/login");
    }

    // -------------------------------------------------------
    // Session dùng chung (issue #44)
    // -------------------------------------------------------

    @Test
    void sessionCreatedOnOneInstance_isAuthenticatedOnTheOther_andItsCsrfTokenIsAccepted() throws Exception {
        User user = createUser(Role.ROLE_USER);
        BrowserSession onA = login(user);                    // đăng nhập ở A
        BrowserSession onB = onA.onInstance(portB());        // request kế tiếp của cùng trình duyệt rơi vào B

        assertThat(onB.get("/profile").statusCode()).isEqualTo(200);
        // Form/fetch POST mang token CSRF lấy từ trang do A render: B đọc cùng token trong session dùng chung. 404 = đã qua
        // CSRF (không phải 403) và đã xác thực (không phải 302 /login) — sản phẩm này không tồn tại
        HttpResponse<String> post = onB.post("/cart/add", Map.of("productId", "999999999", "_csrf", onA.csrfToken()));
        assertThat(post.statusCode()).isEqualTo(404);
        // ... và ngược lại: session vừa được B dùng vẫn hợp lệ ở A
        assertThat(onA.get("/profile").statusCode()).isEqualTo(200);
        // Không có session thì B vẫn đòi đăng nhập
        assertThat(redirectsToLogin(BrowserSession.anonymous(portB()).get("/profile"))).isTrue();
    }

    @Test
    void logoutOnOneInstance_endsTheSessionOnEveryInstance() throws Exception {
        User user = createUser(Role.ROLE_USER);
        BrowserSession onA = login(user);
        BrowserSession onB = onA.onInstance(portB());
        assertThat(onB.get("/profile").statusCode()).isEqualTo(200);

        onA.logout();

        assertThat(redirectsToLogin(onB.get("/profile"))).isTrue();
        assertThat(redirectsToLogin(onA.get("/profile"))).isTrue();
    }

    @Test
    void lockedAccount_isRevokedOnWhicheverInstanceSeesTheNextRequest_andStaysRevokedEverywhere() throws Exception {
        User user = createUser(Role.ROLE_USER);
        BrowserSession onA = login(user);
        BrowserSession onB = onA.onInstance(portB());
        assertThat(onB.get("/profile").statusCode()).isEqualTo(200);

        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            // Request kế tiếp tới B: AccountStatusFilter của B huỷ session (xoá khỏi kho dùng chung)
            assertThat(redirectsToLogin(onB.get("/profile"))).isTrue();

            assertThat(audit.lines()).anySatisfy(line -> assertThat(line).contains(
                    "event.action=SESSION_REVOKED", "event.reason=DISABLED", "username=" + user.getUsername()));
        }
        // Mở khoá lại: session đã bị xoá nên cookie cũ không đăng nhập được ở A
        user.setIsActive(true);
        userRepository.saveAndFlush(user);
        assertThat(redirectsToLogin(onA.get("/profile"))).isTrue();
    }

    @Test
    void webSocketHandshake_worksOnAnInstanceThatDidNotCreateTheSession_andLogoutElsewhereClosesIt() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        BrowserSession onA = login(admin);
        // Handshake + CONNECT (token CSRF của trang do A render) ở B bằng session tạo ở A
        BrowserSession.Stomp socketOnB = track(onA.onInstance(portB()).connect());
        assertThat(socketOnB.session().isConnected()).isTrue();
        socketOnB.subscribe(StompDestinations.ADMIN_ORDERS);
        awaitSubscribed(instanceB.getBean(SimpUserRegistry.class), admin.getUsername(), StompDestinations.ADMIN_ORDERS);

        // Logout ở A: B không nhận event nào — job của B hỏi lại kho session và đóng socket (≤ 1 chu kỳ)
        onA.logout();
        instanceB.getBean(WebSocketSessionRevalidationJob.class).revalidateOpenSessions();

        assertThat(socketOnB.awaitClosed()).isTrue();
    }

    @Test
    void ownPasswordChange_keepsThatBrowserLoggedInUnderANewSessionId_andLogsOutTheOtherBrowser_onEveryInstance()
            throws Exception {
        User user = createUser(Role.ROLE_USER);
        BrowserSession changer = login(user);        // trình duyệt 1, đăng nhập ở A
        BrowserSession otherBrowser = login(user);   // trình duyệt 2 (cookie riêng), cùng tài khoản
        String sessionBeforeChange = changer.sessionCookie();

        // Trình duyệt 1 đổi mật khẩu; request rơi vào B (session, token CSRF và mốc xác thực đều đọc từ kho dùng chung)
        HttpResponse<String> changed = changer.onInstance(portB()).post("/profile/edit", Map.of(
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "phone", "09" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000),
                "currentPassword", PASSWORD,
                "newPassword", "realtime-pass-456-new",
                "confirmNewPassword", "realtime-pass-456-new",
                "_csrf", changer.csrfToken()));
        assertThat(changed.statusCode()).isEqualTo(302);
        assertThat(changed.headers().firstValue("Location").orElse("")).endsWith("/profile");

        // Session vừa đổi mật khẩu: id mới (cookie mới), vẫn đăng nhập ở cả hai instance
        assertThat(changer.sessionCookie()).isNotBlank().isNotEqualTo(sessionBeforeChange);
        assertThat(changer.get("/profile").statusCode()).isEqualTo(200);
        assertThat(changer.onInstance(portB()).get("/profile").statusCode()).isEqualTo(200);

        // Trình duyệt kia (đăng nhập bằng mật khẩu cũ): bị huỷ ở request kế tiếp, tới instance nào cũng vậy
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            assertThat(redirectsToLogin(otherBrowser.get("/profile"))).isTrue();

            assertThat(audit.lines()).anySatisfy(line -> assertThat(line).contains("event.action=SESSION_REVOKED",
                    "event.reason=PASSWORD_CHANGED", "username=" + user.getUsername()));
        }
        assertThat(redirectsToLogin(otherBrowser.onInstance(portB()).get("/profile"))).isTrue();
    }

    // -------------------------------------------------------
    // Sự kiện realtime giữa các instance
    // -------------------------------------------------------

    @Test
    void orderPlacedOnOneInstance_reachesAdminsConnectedToEveryInstance() throws Exception {
        int portB = portB();
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

    @Test
    void statusChangedOnOneInstance_reachesOwnerConnectedToAnotherInstance_only() throws Exception {
        User owner = createUser(Role.ROLE_USER);
        User other = createUser(Role.ROLE_USER);
        Order order = createOrder(owner, OrderStatus.PENDING);
        // Chủ đơn và user khác cùng nối vào A
        BlockingQueue<Map<String, Object>> ownerEvents =
                track(login(owner).connect()).subscribe(StompDestinations.USER_ORDERS);
        BlockingQueue<Map<String, Object>> otherEvents =
                track(login(other).connect()).subscribe(StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, owner.getUsername(), StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, other.getUsername(), StompDestinations.USER_ORDERS);

        // Admin đổi trạng thái trên instance B (outbox của B gửi lên topic sau commit)
        instanceB.getBean(OrderService.class).updateStatus(order.getId(), OrderStatus.COMPLETED);

        Map<String, Object> event = ownerEvents.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(event).as("owner on A").isNotNull().containsEntry("status", "COMPLETED");
        assertThat(longValue(event, "orderId")).isEqualTo(order.getId());
        assertThat(otherEvents.poll(1, TimeUnit.SECONDS)).as("other user on A").isNull();
    }
}
