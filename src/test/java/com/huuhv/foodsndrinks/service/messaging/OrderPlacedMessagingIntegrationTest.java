package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import com.huuhv.foodsndrinks.repository.NotificationLogRepository;
import com.huuhv.foodsndrinks.service.notification.EmailNotificationService;
import com.huuhv.foodsndrinks.service.notification.SlackNotificationService;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Luồng thật: transaction commit → {@link OrderPlacedProducer} → topic {@code order.placed} trên broker embedded → hai
 * subscription (email, Slack) → {@code notification_logs} trên MySQL. Gửi Slack/email là mock.
 * <p>
 * Không {@code @Transactional}: message chỉ được gửi sau commit thật, consumer chạy trên thread khác. Mỗi test dùng
 * {@code orderId} ngẫu nhiên riêng và xoá {@code notification_logs} của nó sau khi chạy. Thời gian chờ giao lại 100ms
 * ({@code artemis/broker-test.properties}); số lần giao (5) và DLQ như production.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedMessagingIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationLogRepository notificationLogRepository;
    @Autowired private JmsListenerEndpointRegistry listenerRegistry;
    @Autowired @Qualifier(JmsBrokerProperties.TOPIC_JMS_TEMPLATE) private JmsTemplate topicJmsTemplate;

    @MockitoBean private SlackNotificationService slackNotificationService;
    @MockitoBean private EmailNotificationService emailNotificationService;

    private final List<Long> orderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(slackNotificationService.isEnabled()).thenReturn(true);
        when(emailNotificationService.isEnabled()).thenReturn(true);
        // Subscription chỉ tồn tại khi consumer đã đăng ký: gửi trước đó thì message mất
        await().atMost(WAIT).until(() -> listenerRegistry.getListenerContainers().stream()
                .allMatch(container -> ((DefaultMessageListenerContainer) container).isRegisteredWithDestination()));
    }

    @AfterEach
    void cleanUp() {
        orderIds.forEach(id -> jdbcTemplate.update("DELETE FROM notification_logs WHERE order_id = ?", id));
    }

    @Test
    void committedOrder_isNotifiedOncePerChannel() {
        long orderId = newOrderId();

        placeOrder(orderId, false);

        verify(slackNotificationService, timeout(WAIT.toMillis())).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, timeout(WAIT.toMillis())).sendNewOrderEmail(order(orderId));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(notificationLogRepository.countByOrderIdAndChannel(orderId, NotificationChannel.SLACK)).isEqualTo(1);
            assertThat(notificationLogRepository.countByOrderIdAndChannel(orderId, NotificationChannel.EMAIL)).isEqualTo(1);
        });
    }

    @Test
    void rolledBackOrder_publishesNothing() {
        long orderId = newOrderId();

        placeOrder(orderId, true);

        verify(slackNotificationService, after(2000).never()).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, never()).sendNewOrderEmail(order(orderId));
    }

    @Test
    void slackFailingOnce_isRedelivered_withoutResendingEmail() {
        long orderId = newOrderId();
        doThrow(new NotificationDeliveryException("Slack webhook returned HTTP 500")).doNothing()
                .when(slackNotificationService).sendOrderPlaced(order(orderId));

        placeOrder(orderId, false);

        verify(slackNotificationService, timeout(WAIT.toMillis()).times(2)).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, after(1000).times(1)).sendNewOrderEmail(order(orderId));
        await().atMost(WAIT).until(() ->
                notificationLogRepository.countByOrderIdAndChannel(orderId, NotificationChannel.SLACK) == 1);
    }

    @Test
    void slackAlwaysFailing_isTriedFiveTimes_thenDeadLettered_andLoggedWithOrderId(CapturedOutput output) {
        long orderId = newOrderId();
        doThrow(new NotificationDeliveryException("Slack webhook returned HTTP 500"))
                .when(slackNotificationService).sendOrderPlaced(order(orderId));

        placeOrder(orderId, false);

        await().atMost(WAIT).until(() -> output.getOut().contains(
                "Notification for order #" + orderId + " dead-lettered from subscription "
                        + Destinations.ORDER_PLACED_SLACK_SUBSCRIPTION));
        verify(slackNotificationService, after(500).times(5)).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, times1()).sendNewOrderEmail(order(orderId));
        // Lần gửi lỗi đã rollback: không có bản ghi, gửi lại sau khi sửa sẽ gửi được
        assertThat(notificationLogRepository.countByOrderIdAndChannel(orderId, NotificationChannel.SLACK)).isZero();
    }

    @Test
    void duplicateDelivery_doesNotNotifyTwice() {
        long orderId = newOrderId();
        doNothing().when(slackNotificationService).sendOrderPlaced(any());

        topicJmsTemplate.convertAndSend(Destinations.ORDER_PLACED, message(orderId));
        topicJmsTemplate.convertAndSend(Destinations.ORDER_PLACED, message(orderId));

        verify(slackNotificationService, after(2000).times(1)).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, times1()).sendNewOrderEmail(order(orderId));
    }

    @Test
    void messageWithUnknownType_isDeadLettered_withoutReachingNotificationServices(CapturedOutput output) {
        topicJmsTemplate.send(Destinations.ORDER_PLACED, session -> {
            var message = session.createTextMessage("{\"orderId\":1}");
            message.setStringProperty("_type", "java.lang.ProcessBuilder");
            return message;
        });

        await().atMost(WAIT).until(() -> output.getOut().split("Unreadable order.placed message dead-lettered", -1)
                .length - 1 == 2);
        verify(slackNotificationService, never()).sendOrderPlaced(any());
        verify(emailNotificationService, never()).sendNewOrderEmail(any());
        // Log của app không chứa alias do bên gửi đặt (broker tự ghi metadata message khi dead-letter — AMQ222149 — là
        // log vận hành của broker, không thuộc phạm vi kiểm tra này)
        assertThat(output.getOut().lines()
                .filter(line -> line.contains("OrderPlacedDlqListener") || line.contains("JmsListenerErrorHandler")))
                .isNotEmpty()
                .noneMatch(line -> line.contains("ProcessBuilder"));
    }

    // -------------------------------------------------------

    /** Publish như {@code OrderService.checkout}: event trong transaction, producer chạy sau commit. */
    private void placeOrder(long orderId, boolean rollback) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(message(orderId));
            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }

    private long newOrderId() {
        long id = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        orderIds.add(id);
        return id;
    }

    private static OrderPlacedMessage message(long orderId) {
        return new OrderPlacedMessage(orderId, "Nguyễn Văn A", "a@example.com", "0901234567", "12 Lê Lợi", null,
                new BigDecimal("120000"), LocalDateTime.of(2026, 11, 23, 9, 30), List.of(
                new OrderPlacedMessage.OrderLineItem("Trà sữa", 2, new BigDecimal("35000"), new BigDecimal("70000"))));
    }

    private static OrderPlacedMessage order(long orderId) {
        return argThat(message -> message != null && message.orderId() == orderId);
    }

    private static org.mockito.verification.VerificationMode times1() {
        return timeout(WAIT.toMillis()).times(1);
    }
}
