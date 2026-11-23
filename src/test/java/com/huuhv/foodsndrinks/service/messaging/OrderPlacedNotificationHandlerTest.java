package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrderPlacedNotificationHandlerTest {

    @Mock private NotificationLogService notificationLogService;
    @InjectMocks private OrderPlacedNotificationHandler handler;

    @SuppressWarnings("unchecked")
    private final Consumer<OrderPlacedMessage> sender = mock(Consumer.class);

    @Test
    void sendsThroughNotificationLog_soDuplicatesAreSkipped() {
        OrderPlacedMessage message = message(42L);
        when(notificationLogService.sendOnce(eq(42L), eq(NotificationChannel.EMAIL), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });

        handler.handle(NotificationChannel.EMAIL, message, 1, () -> true, sender);

        verify(sender).accept(message);
    }

    @Test
    void disabledChannel_isSkipped_withoutRecordingAnything() {
        handler.handle(NotificationChannel.SLACK, message(42L), 1, () -> false, sender);

        verifyNoInteractions(notificationLogService, sender);
    }

    @Test
    void messageWithoutValidOrderId_isDropped_notRedelivered() {
        handler.handle(NotificationChannel.SLACK, message(null), 1, () -> true, sender);
        handler.handle(NotificationChannel.SLACK, message(0L), 1, () -> true, sender);
        handler.handle(NotificationChannel.SLACK, null, 1, () -> true, sender);

        verifyNoInteractions(notificationLogService, sender);
    }

    @Test
    void failure_isLoggedWithOrderId_andRethrown_soBrokerRedelivers(CapturedOutput output) {
        when(notificationLogService.sendOnce(anyLong(), any(), any()))
                .thenThrow(new NotificationDeliveryException("Slack webhook returned HTTP 500"));

        assertThatThrownBy(() -> handler.handle(NotificationChannel.SLACK, message(42L), 3, () -> true, sender))
                .isInstanceOf(NotificationDeliveryException.class);

        assertThat(output).contains("SLACK notification for order #42 failed on delivery 3")
                .contains("Slack webhook returned HTTP 500");
    }

    private static OrderPlacedMessage message(Long orderId) {
        return new OrderPlacedMessage(orderId, "A", "a@example.com", null, "HN", null, BigDecimal.TEN, null, List.of());
    }
}
