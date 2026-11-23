package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedDlqListenerTest {

    private final MessageConverter converter = mock(MessageConverter.class);
    private final OrderPlacedDlqListener listener = new OrderPlacedDlqListener(converter);

    @Test
    void readableMessage_loggedAsErrorWithOrderIdAndSubscription(CapturedOutput output) throws Exception {
        TextMessage message = deadLetter("order-placed-slack", "{}");
        when(converter.fromMessage(message)).thenReturn(new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN",
                null, BigDecimal.TEN, null, List.of()));

        listener.onDeadLetter(message);

        assertThat(output).contains("ERROR")
                .contains("Notification for order #42 dead-lettered from subscription order-placed-slack");
    }

    @Test
    void unreadableMessage_loggedWithoutPayload(CapturedOutput output) throws Exception {
        TextMessage message = deadLetter("order-placed-email\nFAKE LOG LINE", "{\"secret\":\"PAYLOAD\"}");
        when(converter.fromMessage(message))
                .thenThrow(new MessageConversionException("Missing or unknown message type alias on JMS message ID:1"));

        listener.onDeadLetter(message);

        assertThat(output).contains("ERROR").contains("Unreadable order.placed message dead-lettered")
                .contains("body length 20")
                .doesNotContain("PAYLOAD")
                // tên subscription lấy từ message → đã làm sạch, không giả được dòng log mới
                .doesNotContain("\nFAKE LOG LINE");
    }

    private static TextMessage deadLetter(String originalQueue, String body) throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(OrderPlacedDlqListener.ORIGINAL_QUEUE_PROPERTY)).thenReturn(originalQueue);
        when(message.getText()).thenReturn(body);
        return message;
    }
}
