package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.service.websocket.AdminOrderNotifier;
import com.huuhv.foodsndrinks.service.websocket.OrderStatusNotifier;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Message hợp lệ → chủ đơn trước, admin sau (DB lỗi khi đếm đơn chờ không làm mất thông báo của chủ đơn); message
 * rỗng/không hợp lệ bị bỏ, log chỉ có tên field.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderStatusChangedWebSocketConsumerTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    private final OrderStatusNotifier ownerNotifier = mock(OrderStatusNotifier.class);
    private final AdminOrderNotifier adminNotifier = mock(AdminOrderNotifier.class);
    private final Validator validator = VALIDATORS.getValidator();
    private final OrderStatusChangedWebSocketConsumer consumer =
            new OrderStatusChangedWebSocketConsumer(ownerNotifier, adminNotifier, validator);

    @AfterAll
    static void closeFactory() {
        VALIDATORS.close();
    }

    @Test
    void validMessage_notifiesOwnerThenAdmins() {
        OrderStatusChangedMessage message = new OrderStatusChangedMessage(42L, "alice", OrderStatus.COMPLETED);

        consumer.onOrderStatusChanged(message);

        InOrder order = inOrder(ownerNotifier, adminNotifier);
        order.verify(ownerNotifier).notifyOwner(message);
        order.verify(adminNotifier).orderStatusChanged(message);
    }

    @Test
    void adminNotificationFailure_doesNotPreventOwnerNotification() {
        OrderStatusChangedMessage message = new OrderStatusChangedMessage(42L, "alice", OrderStatus.CANCELLED);
        doThrow(new IllegalStateException("db down")).when(adminNotifier).orderStatusChanged(any());

        assertThatThrownBy(() -> consumer.onOrderStatusChanged(message)).isInstanceOf(IllegalStateException.class);

        verify(ownerNotifier).notifyOwner(message);
    }

    @Test
    void invalidMessage_isIgnored_withFieldNamesOnly(CapturedOutput output) {
        consumer.onOrderStatusChanged(new OrderStatusChangedMessage(-1L, " ", null));
        consumer.onOrderStatusChanged(null);

        verifyNoInteractions(ownerNotifier, adminNotifier);
        assertThat(output).contains("invalid orderId, status, username");
    }
}
