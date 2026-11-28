package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.service.notification.EmailNotificationService;
import com.huuhv.foodsndrinks.service.notification.SlackNotificationService;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

/**
 * Nền chung của integration test JMS: MySQL Testcontainers + broker embedded, gửi Slack/email là mock, template topic và
 * {@link JmsBrokerProbe} là spy (để giả lập broker lỗi). Các lớp con khai báo đúng bộ override này nên dùng chung MỘT Spring context.
 * <p>
 * Không {@code @Transactional}: message chỉ được gửi sau commit thật, consumer chạy trên thread khác. Mỗi test dùng
 * {@code orderId} ngẫu nhiên riêng và tự xoá {@code notification_logs}/{@code outbox_messages} của nó. Thời gian chờ giao
 * lại 100ms ({@code artemis/broker-test.properties}); số lần giao (5) và DLQ như production.
 * <p>
 * {@code public} để integration test JMS ở package khác (vd. {@code service.excel}) kế thừa và dùng chung context này.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
public abstract class AbstractMessagingIntegrationTest {

    protected static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired protected OutboxService outboxService;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired private JmsListenerEndpointRegistry listenerRegistry;

    @MockitoBean protected SlackNotificationService slackNotificationService;
    @MockitoBean protected EmailNotificationService emailNotificationService;
    @MockitoSpyBean(name = JmsBrokerProperties.TOPIC_JMS_TEMPLATE) protected JmsTemplate topicJmsTemplate;
    @MockitoSpyBean protected JmsBrokerProbe brokerProbe;

    private final List<Long> orderIds = new ArrayList<>();

    @BeforeEach
    protected void setUpMessaging() {
        when(slackNotificationService.isEnabled()).thenReturn(true);
        when(emailNotificationService.isEnabled()).thenReturn(true);
        // Subscription chỉ tồn tại khi consumer đã đăng ký: gửi trước đó thì message mất
        await().atMost(WAIT).until(() -> listenerRegistry.getListenerContainers().stream()
                .allMatch(container -> ((DefaultMessageListenerContainer) container).isRegisteredWithDestination()));
    }

    @AfterEach
    protected void cleanUpMessaging() {
        for (Long id : orderIds) {
            jdbcTemplate.update("DELETE FROM notification_logs WHERE order_id = ?", id);
            jdbcTemplate.update("DELETE FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?", id);
        }
    }

    /** Như {@code OrderService.checkout}: ghi outbox trong transaction; commit thì được gửi ngay sau đó. */
    protected void placeOrder(long orderId, boolean rollback) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxService.enqueue(OutboxType.ORDER_PLACED, message(orderId));
            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }

    protected long newOrderId() {
        long id = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        orderIds.add(id);
        return id;
    }

    protected Long outboxIdOf(long orderId) {
        return jdbcTemplate.queryForList("SELECT id FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?",
                Long.class, orderId).stream().findFirst().orElse(null);
    }

    protected String outboxStatusOf(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?", String.class, orderId)
                .stream().findFirst().orElse(null);
    }

    /** Số dòng {@code notification_logs} của (đơn, kênh) — đếm bằng SQL, code chính không cần truy vấn này. */
    protected long notificationsLogged(long orderId, NotificationChannel channel) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_logs WHERE order_id = ? AND channel = ?",
                Long.class, orderId, channel.name());
    }

    protected static OrderPlacedMessage message(long orderId) {
        return new OrderPlacedMessage(orderId, "Nguyễn Văn A", "a@example.com", "0901234567", "12 Lê Lợi", null,
                new BigDecimal("120000.00"), LocalDateTime.of(2026, 11, 23, 9, 30), List.of(
                new OrderPlacedMessage.OrderLineItem("Trà sữa", 2, new BigDecimal("35000"), new BigDecimal("70000"))));
    }

    /** Matcher Mockito: message của đơn {@code orderId}. */
    protected static OrderPlacedMessage order(long orderId) {
        return argThat(message -> message != null && message.orderId() == orderId);
    }
}
