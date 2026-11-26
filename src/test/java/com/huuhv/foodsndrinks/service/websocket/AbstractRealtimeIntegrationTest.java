package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.support.BrowserSession;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;

/**
 * Nền của integration test realtime: Tomcat thật (cổng ngẫu nhiên) + MySQL Testcontainers + broker Artemis embedded;
 * client là {@link BrowserSession} (đăng nhập form + STOMP qua WebSocket như trình duyệt).
 * <p>
 * Không {@code @Transactional}: Tomcat xử lý request trên thread khác, chỉ thấy dữ liệu đã commit — user tạo với tên ngẫu
 * nhiên và tự xoá sau mỗi test. Các lớp con không khai báo override nào để dùng chung MỘT context.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractRealtimeIntegrationTest {

    protected static final String PASSWORD = "realtime-pass-123";

    @Value("${local.server.port}") protected int port;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected SimpUserRegistry userRegistry;
    @Autowired protected JmsListenerEndpointRegistry listenerRegistry;
    @Autowired @Qualifier(JmsBrokerProperties.TOPIC_JMS_TEMPLATE) protected JmsTemplate topicJmsTemplate;

    @Autowired protected OrderRepository orderRepository;
    @Autowired protected JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();
    private final List<BrowserSession.Stomp> connections = new ArrayList<>();

    @BeforeEach
    void awaitJmsSubscriptions() {
        // Subscription non-durable chỉ tồn tại khi consumer đã đăng ký: gửi trước đó thì message mất
        awaitRegistered(listenerRegistry);
    }

    @AfterEach
    void cleanUp() {
        connections.forEach(BrowserSession.Stomp::close);
        orderIds.forEach(id -> {
            jdbcTemplate.update("DELETE FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?", id);
            orderRepository.deleteById(id);
        });
        userIds.forEach(userRepository::deleteById);
    }

    /** Đơn đã đặt (đã commit — Tomcat và listener JMS chạy trên thread khác), tự xoá sau test. */
    protected Order createOrder(User owner, OrderStatus status) {
        LocalDateTime now = LocalDateTime.now();
        Order order = orderRepository.saveAndFlush(Order.builder()
                .user(owner)
                .status(status)
                .totalPrice(new BigDecimal("120000"))
                .shippingAddress("12 Lê Lợi")
                .orderedAt(now)
                .build());
        orderIds.add(order.getId());
        return order;
    }

    protected static void awaitRegistered(JmsListenerEndpointRegistry registry) {
        await().atMost(BrowserSession.WAIT).until(() -> registry.getListenerContainers().stream()
                .allMatch(container -> ((DefaultMessageListenerContainer) container).isRegisteredWithDestination()));
    }

    protected User createUser(Role role) {
        String username = "rt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .fullName("Realtime Test")
                .role(role)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    protected BrowserSession login(User user) throws Exception {
        return login(port, user);
    }

    protected BrowserSession login(int serverPort, User user) throws Exception {
        return BrowserSession.login(serverPort, user.getUsername(), PASSWORD);
    }

    /** Kết nối được dọn sau test. */
    protected BrowserSession.Stomp track(BrowserSession.Stomp stomp) {
        connections.add(stomp);
        return stomp;
    }

    /** SUBSCRIBE không có RECEIPT: chờ tới khi server ghi nhận subscription rồi mới phát sự kiện. */
    protected void awaitSubscribed(SimpUserRegistry registry, String username, String destination) {
        await().atMost(BrowserSession.WAIT).until(() -> !registry.findSubscriptions(subscription ->
                destination.equals(subscription.getDestination())
                        && username.equals(subscription.getSession().getUser().getName())).isEmpty());
    }

    protected static long newOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    }

    /** Số trong payload JSON đọc thành {@code Map}: Jackson trả Integer hoặc Long tuỳ độ lớn → so theo giá trị. */
    protected static long longValue(Map<String, Object> payload, String field) {
        return ((Number) payload.get(field)).longValue();
    }

    protected static OrderPlacedMessage orderPlaced(long orderId) {
        return new OrderPlacedMessage(orderId, "Nguyễn Văn A", "a@example.com", "0901234567", "12 Lê Lợi", null,
                new BigDecimal("120000.00"), LocalDateTime.of(2026, 11, 26, 9, 30), List.of(
                new OrderPlacedMessage.OrderLineItem("Trà sữa", 2, new BigDecimal("35000"), new BigDecimal("70000")),
                new OrderPlacedMessage.OrderLineItem("Bánh mì", 1, new BigDecimal("50000"), new BigDecimal("50000"))));
    }
}
