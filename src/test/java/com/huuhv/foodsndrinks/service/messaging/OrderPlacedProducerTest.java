package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedProducerTest {

    private final JmsTemplate topicJmsTemplate = mock(JmsTemplate.class);
    private final OrderPlacedProducer producer = new OrderPlacedProducer(topicJmsTemplate);
    private final OrderPlacedMessage message =
            new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN", null, BigDecimal.TEN, null, List.of());

    @Test
    void publishesToOrderPlacedTopic() {
        producer.onOrderPlaced(message);

        verify(topicJmsTemplate).convertAndSend(Destinations.ORDER_PLACED, message);
    }

    @Test
    void brokerDown_isLoggedWithOrderId_notThrownToCheckout(CapturedOutput output) {
        doThrow(new UncategorizedJmsException("connection refused"))
                .when(topicJmsTemplate).convertAndSend(Destinations.ORDER_PLACED, message);

        assertThatCode(() -> producer.onOrderPlaced(message)).doesNotThrowAnyException();

        assertThat(output).contains("ERROR").contains("Failed to publish order.placed for order #42");
    }
}
