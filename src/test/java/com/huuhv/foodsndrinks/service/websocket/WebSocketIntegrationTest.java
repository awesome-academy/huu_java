package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import com.huuhv.foodsndrinks.service.messaging.Destinations;
import com.huuhv.foodsndrinks.support.BrowserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WebSocket/STOMP end-to-end trên Tomcat thật: admin nhận đơn mới qua topic JMS → STOMP; các lớp bảo mật (đăng nhập,
 * phân quyền destination, CSRF ở CONNECT, origin, chặn SEND) và thu hồi kết nối (logout, khoá tài khoản).
 */
class WebSocketIntegrationTest extends AbstractRealtimeIntegrationTest {

    @Autowired private WebSocketSessionRevalidationJob revalidationJob;
    @Autowired private OrderService orderService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SimpleBrokerMessageHandler simpleBroker;

    @Test
    void admin_receivesNewOrder_withPendingCount_andNoCustomerData() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        BrowserSession.Stomp stomp = track(login(admin).connect());
        BlockingQueue<Map<String, Object>> events = stomp.subscribe(StompDestinations.ADMIN_ORDERS);
        awaitSubscribed(userRegistry, admin.getUsername(), StompDestinations.ADMIN_ORDERS);
        long orderId = newOrderId();

        topicJmsTemplate.convertAndSend(Destinations.ORDER_PLACED, orderPlaced(orderId));

        Map<String, Object> event = events.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event).containsEntry("type", "ORDER_PLACED")
                .containsEntry("itemCount", 3)
                .containsKey("pendingCount");
        assertThat(longValue(event, "orderId")).isEqualTo(orderId);
        assertThat(event.toString()).doesNotContain("Nguyễn Văn A", "a@example.com", "0901234567", "Lê Lợi");
    }

    @Test
    void user_cannotSubscribeToAdminTopic() throws Exception {
        User user = createUser(Role.ROLE_USER);
        BrowserSession.Stomp stomp = track(login(user).connect());
        stomp.session();

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            stomp.subscribe(StompDestinations.ADMIN_ORDERS);

            StompHeaders error = stomp.nextError();
            assertThat(error).isNotNull();
            assertThat(error.getFirst("message")).isEqualTo("Access denied");
            assertThat(stomp.awaitClosed()).isTrue();
            assertThat(audit.lines()).anySatisfy(line -> assertThat(line)
                    .contains("event.action=WEBSOCKET_ACCESS_DENIED", "stomp.command=SUBSCRIBE",
                            "stomp.destination=" + StompDestinations.ADMIN_ORDERS, "username=" + user.getUsername()));
        }
        assertThat(userRegistry.findSubscriptions(subscription ->
                user.getUsername().equals(subscription.getSession().getUser().getName()))).isEmpty();
    }

    @Test
    void anonymous_cannotOpenWebSocket() throws Exception {
        Throwable failure = track(BrowserSession.anonymous(port).connect()).failure();

        // Chain web chuyển hướng tới /login như mọi trang cần đăng nhập (client theo redirect, nhận trang login) →
        // không upgrade được
        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining("did not permit the HTTP upgrade");
    }

    @Test
    void connectWithoutOrWithWrongCsrfToken_isRejected_withoutEchoingToken() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        BrowserSession browser = login(admin);

        BrowserSession.Stomp missing = track(browser.connect(null, null));
        assertThat(missing.nextError().getFirst("message")).isEqualTo("Access denied");
        assertThat(missing.failure()).isNotNull();

        BrowserSession.Stomp wrong = track(browser.connect("forged-token-value", null));
        StompHeaders error = wrong.nextError();
        assertThat(error.getFirst("message")).isEqualTo("Access denied");
        assertThat(error.toString()).doesNotContain("forged-token-value", browser.csrfToken());
        assertThat(wrong.failure()).isNotNull();
    }

    @Test
    void handshakeFromForeignOrigin_isRejected() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        BrowserSession browser = login(admin);

        Throwable failure = track(browser.connect(browser.csrfToken(), "https://evil.example.com")).failure();
        assertThat(failure).isNotNull();
        assertThat(failure).hasStackTraceContaining("403");

        BrowserSession.Stomp sameOrigin = track(browser.connect(browser.csrfToken(), "http://localhost:" + port));
        assertThat(sameOrigin.session().isConnected()).isTrue();
    }

    @Test
    void clientCannotPublishFakeEvents() throws Exception {
        User listener = createUser(Role.ROLE_ADMIN);
        User attacker = createUser(Role.ROLE_ADMIN);
        BrowserSession.Stomp listenerStomp = track(login(listener).connect());
        BlockingQueue<Map<String, Object>> events = listenerStomp.subscribe(StompDestinations.ADMIN_ORDERS);
        awaitSubscribed(userRegistry, listener.getUsername(), StompDestinations.ADMIN_ORDERS);
        BrowserSession.Stomp attackerStomp = track(login(attacker).connect());

        attackerStomp.session().send(StompDestinations.ADMIN_ORDERS,
                Map.of("type", "ORDER_PLACED", "orderId", 1, "pendingCount", 999));

        assertThat(attackerStomp.nextError().getFirst("message")).isEqualTo("Access denied");
        assertThat(events.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void logout_closesOpenWebSocket() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        BrowserSession browser = login(admin);
        BrowserSession.Stomp stomp = track(browser.connect());
        stomp.session();

        browser.logout();

        assertThat(stomp.awaitClosed()).isTrue();
    }

    @Test
    void loginAgainInSameBrowser_closesOpenSocket_andReconnectNeedsFreshCsrfToken() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        User other = createUser(Role.ROLE_USER);
        BrowserSession browser = login(admin);
        BrowserSession.Stomp adminTab = track(browser.connect());
        adminTab.session();
        String pageToken = browser.csrfToken();

        // Tab khác của cùng trình duyệt đăng nhập bằng tài khoản khác: id session đổi, token CSRF đổi
        browser.signInAgain(other.getUsername(), PASSWORD);

        // Socket mang danh tính admin lúc handshake bị đóng ngay (HttpSessionIdChangedEvent), không chờ job
        assertThat(adminTab.awaitClosed()).isTrue();
        // Nối lại bằng token trong meta của trang cũ → bị từ chối mãi (lỗi review: realtime chết tới khi tải lại trang)
        BrowserSession.Stomp stale = track(browser.connect(pageToken, null));
        assertThat(stale.nextError().getFirst("message")).isEqualTo("Access denied");
        // realtime.js lấy token mới qua GET /csrf-token trước khi nối lại → kết nối được, với danh tính mới
        BrowserSession.Stomp fresh = track(browser.connect(browser.fetchCsrfToken(), null));
        assertThat(fresh.session().isConnected()).isTrue();
        await().atMost(BrowserSession.WAIT).until(() -> userRegistry.getUser(other.getUsername()) != null);
    }

    @Test
    void statusChange_reachesOnlyTheOrderOwner_andAdmins_afterCommit() throws Exception {
        User owner = createUser(Role.ROLE_USER);
        User other = createUser(Role.ROLE_USER);
        User admin = createUser(Role.ROLE_ADMIN);
        Order order = createOrder(owner, OrderStatus.PENDING);
        BrowserSession.Stomp ownerStomp = track(login(owner).connect());
        BrowserSession.Stomp otherStomp = track(login(other).connect());
        BlockingQueue<Map<String, Object>> ownerEvents = ownerStomp.subscribe(StompDestinations.USER_ORDERS);
        BlockingQueue<Map<String, Object>> otherEvents = otherStomp.subscribe(StompDestinations.USER_ORDERS);
        BlockingQueue<Map<String, Object>> adminEvents =
                track(login(admin).connect()).subscribe(StompDestinations.ADMIN_ORDERS);
        awaitSubscribed(userRegistry, owner.getUsername(), StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, other.getUsername(), StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, admin.getUsername(), StompDestinations.ADMIN_ORDERS);

        // Admin đổi trạng thái (transaction riêng của service, commit) → outbox → topic JMS → STOMP
        orderService.updateStatus(order.getId(), OrderStatus.PROCESSING);

        Map<String, Object> event = ownerEvents.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(longValue(event, "orderId")).isEqualTo(order.getId());
        assertThat(event).containsEntry("status", "PROCESSING")
                .containsEntry("statusLabel", OrderStatus.PROCESSING.getLabel())
                .containsEntry("badgeClass", OrderStatus.PROCESSING.getBadgeClass());
        Map<String, Object> adminEvent = adminEvents.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(adminEvent).isNotNull().containsEntry("type", "ORDER_STATUS_CHANGED").containsKey("pendingCount");
        assertThat(longValue(adminEvent, "orderId")).isEqualTo(order.getId());
        // IDOR: user khác đang online cùng đích /user/queue/orders không nhận gì
        assertThat(otherEvents.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(ownerEvents.poll(500, TimeUnit.MILLISECONDS)).as("exactly one event").isNull();
    }

    @Test
    void staleStatusEventDeliveredLate_ownerStillGetsCurrentStatusFromDb() throws Exception {
        User owner = createUser(Role.ROLE_USER);
        Order order = createOrder(owner, OrderStatus.COMPLETED);
        BlockingQueue<Map<String, Object>> ownerEvents =
                track(login(owner).connect()).subscribe(StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, owner.getUsername(), StompDestinations.USER_ORDERS);

        // Message của lần đổi trước (PROCESSING) tới sau cùng (gửi lại bởi OutboxRelayJob) trong khi DB đã COMPLETED
        topicJmsTemplate.convertAndSend(Destinations.ORDER_STATUS_CHANGED,
                new OrderStatusChangedMessage(order.getId(), owner.getUsername(), OrderStatus.PROCESSING));

        Map<String, Object> event = ownerEvents.poll(BrowserSession.WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(event).isNotNull().containsEntry("status", "COMPLETED");
        // Thứ tự gửi tới từng client được giữ (kênh outbound mặc định là pool nhiều thread)
        assertThat(simpleBroker.isPreservePublishOrder()).isTrue();
    }

    @Test
    void floodOfSubscribes_isCutOff_atDuplicateDestination() throws Exception {
        User user = createUser(Role.ROLE_USER);
        BrowserSession.Stomp stomp = track(login(user).connect());
        stomp.subscribe(StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, user.getUsername(), StompDestinations.USER_ORDERS);

        // SUBSCRIBE thứ hai cùng đích (id mới): simple broker sẽ thêm bản ghi và nhân số message — bị từ chối, đóng kết nối
        stomp.subscribe(StompDestinations.USER_ORDERS);

        assertThat(stomp.nextError().getFirst("message")).isEqualTo("Message could not be processed");
        assertThat(stomp.awaitClosed()).isTrue();
    }

    @Test
    void otherUser_cannotSubscribeToOwnersUserDestination() throws Exception {
        User owner = createUser(Role.ROLE_USER);
        User other = createUser(Role.ROLE_USER);
        BrowserSession.Stomp otherStomp = track(login(other).connect());

        otherStomp.subscribe("/user/" + owner.getUsername() + "/queue/orders");

        assertThat(otherStomp.nextError().getFirst("message")).isEqualTo("Access denied");
    }

    @Test
    void rolledBackStatusChange_notifiesNobody() throws Exception {
        User owner = createUser(Role.ROLE_USER);
        Order order = createOrder(owner, OrderStatus.PENDING);
        BlockingQueue<Map<String, Object>> ownerEvents =
                track(login(owner).connect()).subscribe(StompDestinations.USER_ORDERS);
        awaitSubscribed(userRegistry, owner.getUsername(), StompDestinations.USER_ORDERS);

        transactionTemplate.executeWithoutResult(tx -> {
            orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);
            tx.setRollbackOnly();
        });

        assertThat(ownerEvents.poll(2, TimeUnit.SECONDS)).isNull();
        assertThat(orderRepository.findById(order.getId())).get()
                .extracting(Order::getStatus).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void lockedOrDemotedAccount_isDisconnectedByRevalidationJob() throws Exception {
        User admin = createUser(Role.ROLE_ADMIN);
        User other = createUser(Role.ROLE_ADMIN);
        BrowserSession.Stomp demoted = track(login(admin).connect());
        BrowserSession.Stomp untouched = track(login(other).connect());
        demoted.session();
        untouched.session();

        admin.setRole(Role.ROLE_USER);
        userRepository.saveAndFlush(admin);
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            revalidationJob.revalidateOpenSessions();

            assertThat(demoted.awaitClosed()).isTrue();
            assertThat(audit.lines()).anySatisfy(line -> assertThat(line)
                    .contains("event.action=SESSION_REVOKED", "event.reason=ROLE_CHANGED", "session.type=websocket",
                            "username=" + admin.getUsername()));
        }
        assertThat(untouched.isConnected()).isTrue();
    }
}
