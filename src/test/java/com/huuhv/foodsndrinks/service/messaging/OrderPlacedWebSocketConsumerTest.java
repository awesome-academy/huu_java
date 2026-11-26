package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.service.websocket.AdminOrderNotifier;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import jakarta.validation.ValidatorFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Message hợp lệ được đẩy tới admin; message rỗng/không hợp lệ bị bỏ, log chỉ có tên field. */
@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedWebSocketConsumerTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    private final AdminOrderNotifier notifier = mock(AdminOrderNotifier.class);
    private final Validator validator = VALIDATORS.getValidator();
    private final OrderPlacedWebSocketConsumer consumer = new OrderPlacedWebSocketConsumer(notifier, validator);

    @AfterAll
    static void closeFactory() {
        VALIDATORS.close();
    }

    @Test
    void validMessage_isForwardedToAdmins() {
        OrderPlacedMessage message = message(42L, List.of());

        consumer.onOrderPlaced(message);

        verify(notifier).orderPlaced(message);
    }

    @Test
    void invalidMessage_isIgnored_withFieldNamesOnly(CapturedOutput output) {
        consumer.onOrderPlaced(message(-1L, null));
        consumer.onOrderPlaced(null);

        verifyNoInteractions(notifier);
        assertThat(output).contains("invalid items, orderId").doesNotContain("Secret Customer");
    }

    private static OrderPlacedMessage message(Long orderId, List<OrderPlacedMessage.OrderLineItem> items) {
        return new OrderPlacedMessage(orderId, "Secret Customer", null, null, null, null, BigDecimal.TEN,
                LocalDateTime.of(2026, 11, 26, 9, 0), items);
    }
}
