package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.enums.NotificationChannel;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Mock private NotificationLogService notificationLogService;
    private OrderPlacedNotificationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrderPlacedNotificationHandler(notificationLogService, VALIDATOR);
    }

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
    void messageWithoutItems_isDropped_insteadOfFailingOnEveryDelivery(CapturedOutput output) {
        // Không validate: formatter ném NPE ở mọi lần giao (× 2 kênh × 5 lần) rồi mới vào DLQ
        OrderPlacedMessage noItems = new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN", null,
                BigDecimal.TEN, null, null);
        List<OrderPlacedMessage.OrderLineItem> nullItem = new ArrayList<>();
        nullItem.add(null);
        OrderPlacedMessage withNullItem = new OrderPlacedMessage(43L, "A", "a@example.com", null, "HN", null,
                BigDecimal.TEN, null, nullItem);

        handler.handle(NotificationChannel.EMAIL, noItems, 1, () -> true, sender);
        handler.handle(NotificationChannel.EMAIL, withNullItem, 1, () -> true, sender);

        verifyNoInteractions(notificationLogService, sender);
        assertThat(output).contains("WARN").contains("Ignoring invalid order.placed message (EMAIL notification)")
                .contains("invalid items").contains("items[0]");
    }

    @Test
    void optionalFieldsMissing_isStillSent() {
        // Chỉ orderId + items là bắt buộc: khách đăng nhập bằng X có thể không có email
        OrderPlacedMessage message = new OrderPlacedMessage(42L, null, null, null, null, null, null, null, List.of());
        when(notificationLogService.sendOnce(eq(42L), eq(NotificationChannel.SLACK), any())).thenReturn(true);

        handler.handle(NotificationChannel.SLACK, message, 1, () -> true, sender);

        verify(notificationLogService).sendOnce(eq(42L), eq(NotificationChannel.SLACK), any());
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
