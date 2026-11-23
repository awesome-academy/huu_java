package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import com.huuhv.foodsndrinks.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Luồng thật: transaction commit → outbox → topic {@code order.placed} trên broker embedded → hai subscription (email,
 * Slack) → {@code notification_logs} trên MySQL.
 */
class OrderPlacedMessagingIntegrationTest extends AbstractMessagingIntegrationTest {

    @Autowired private NotificationLogRepository notificationLogRepository;
    @Autowired private OutboxPublisher outboxPublisher;

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
        assertThat(outboxIdOf(orderId)).isNull();
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
        verify(emailNotificationService, timeout(WAIT.toMillis()).times(1)).sendNewOrderEmail(order(orderId));
        // Lần gửi lỗi đã rollback: không có bản ghi, gửi lại sau khi sửa sẽ gửi được
        assertThat(notificationLogRepository.countByOrderIdAndChannel(orderId, NotificationChannel.SLACK)).isZero();
        // Bản ghi outbox được giữ lại (job chỉ xoá SENT) để gửi lại bằng tay
        await().atMost(WAIT).until(() -> "DEAD_LETTERED".equals(outboxStatusOf(orderId)));

        // Sửa Slack rồi đặt lại về PENDING (README) → chỉ Slack được gửi lại, email không gửi lần hai
        reset(slackNotificationService);
        when(slackNotificationService.isEnabled()).thenReturn(true);
        jdbcTemplate.update("UPDATE outbox_messages SET status = 'PENDING', attempts = 0 WHERE id = ?",
                outboxIdOf(orderId));
        outboxPublisher.publishDue();

        verify(slackNotificationService, timeout(WAIT.toMillis())).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, after(1000).times(1)).sendNewOrderEmail(order(orderId));
        assertThat(outboxStatusOf(orderId)).isEqualTo("SENT");
    }

    @Test
    void duplicateDelivery_doesNotNotifyTwice() {
        long orderId = newOrderId();
        doNothing().when(slackNotificationService).sendOrderPlaced(any());

        topicJmsTemplate.convertAndSend(Destinations.ORDER_PLACED, message(orderId));
        topicJmsTemplate.convertAndSend(Destinations.ORDER_PLACED, message(orderId));

        verify(slackNotificationService, after(2000).times(1)).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, timeout(WAIT.toMillis()).times(1)).sendNewOrderEmail(order(orderId));
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
}
