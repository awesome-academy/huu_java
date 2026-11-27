package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.MessageConverter;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class OrderPlacedDlqListenerTest {

    private final MessageConverter converter = mock(MessageConverter.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final OrderPlacedDlqListener listener = new OrderPlacedDlqListener(converter, outboxService);

    @Test
    void readableMessage_loggedAsErrorWithOrderIdAndSubscription_andOutboxRecordKept(CapturedOutput output)
            throws Exception {
        TextMessage message = deadLetter("order-placed-slack", "{}");
        withOutboxId(message, 9L);
        when(outboxService.markDeadLettered(9L)).thenReturn(true);
        when(converter.fromMessage(message)).thenReturn(new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN",
                null, BigDecimal.TEN, null, List.of()));

        listener.onDeadLetter(message);

        assertThat(output).contains("ERROR")
                .contains("Notification for order #42 dead-lettered from subscription order-placed-slack")
                .contains("(outbox #9)");
        // Job dọn dẹp chỉ xoá SENT → bản ghi còn để gửi lại sau khi sửa kênh lỗi
        verify(outboxService).markDeadLettered(9L);
    }

    @Test
    void messageNotSentThroughOutbox_marksNothing() throws Exception {
        TextMessage message = deadLetter("order-placed-email", "{}");
        when(converter.fromMessage(message)).thenReturn(new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN",
                null, BigDecimal.TEN, null, List.of()));

        listener.onDeadLetter(message);

        verifyNoInteractions(outboxService);
    }

    @Test
    void outboxRecordNoLongerSent_isLeftUnchanged_withWarning(CapturedOutput output) throws Exception {
        TextMessage message = deadLetter("order-placed-email", "{}");
        withOutboxId(message, 9L);
        when(converter.fromMessage(message)).thenReturn(new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN",
                null, BigDecimal.TEN, null, List.of()));

        listener.onDeadLetter(message);

        assertThat(output).contains("WARN").contains("Outbox message #9 is no longer SENT");
    }

    @Test
    void sameMessageDeadLetteredByBothSubscriptions_noWarning(CapturedOutput output) throws Exception {
        // OutboxService coi bản ghi đã DEAD_LETTERED là thành công → lần DLQ thứ hai không báo "đã bị reset"
        TextMessage fromEmail = deadLetter("order-placed-email", "{}");
        TextMessage fromSlack = deadLetter("order-placed-slack", "{}");
        withOutboxId(fromEmail, 9L);
        withOutboxId(fromSlack, 9L);
        when(outboxService.markDeadLettered(9L)).thenReturn(true);
        when(converter.fromMessage(any())).thenThrow(new MessageConversionException("Unknown alias"));

        listener.onDeadLetter(fromEmail);
        listener.onDeadLetter(fromSlack);

        verify(outboxService, times(2)).markDeadLettered(9L);
        assertThat(output).doesNotContain("no longer SENT");
    }

    @Test
    void unreadableMessage_loggedWithoutPayload_andOutboxRecordKept(CapturedOutput output) throws Exception {
        TextMessage message = deadLetter("order-placed-email\nFAKE LOG LINE", "{\"secret\":\"PAYLOAD\"}");
        withOutboxId(message, 9L);
        when(outboxService.markDeadLettered(9L)).thenReturn(true);
        when(converter.fromMessage(message))
                .thenThrow(new MessageConversionException("Missing or unknown message type alias on JMS message ID:1"));

        listener.onDeadLetter(message);

        verify(outboxService).markDeadLettered(9L);
        assertThat(output).contains("ERROR").contains("Unreadable order.placed message dead-lettered")
                .contains("body length 20")
                .doesNotContain("PAYLOAD")
                // tên subscription lấy từ message → đã làm sạch, không giả được dòng log mới
                .doesNotContain("\nFAKE LOG LINE");
    }

    @Test
    void jmsErrorWhileReadingMessage_isLoggedAsUnreadable_insteadOfRedeliveredForever(CapturedOutput output)
            throws Exception {
        // DLQ thử lại không giới hạn: ném ra vì lỗi của chính message thì message không bao giờ rời DLQ
        TextMessage message = deadLetter("order-placed-email", "{}");
        withOutboxId(message, 9L);
        when(outboxService.markDeadLettered(9L)).thenReturn(true);
        when(message.getStringProperty(OrderPlacedDlqListener.ORIGINAL_QUEUE_PROPERTY))
                .thenThrow(new MessageFormatException("bad queue property"));
        when(message.getJMSTimestamp()).thenThrow(new JMSException("bad timestamp"));
        when(converter.fromMessage(message)).thenThrow(new MessageFormatException("Invalid conversion SECRET-PROPERTY"));

        assertThatCode(() -> listener.onDeadLetter(message)).doesNotThrowAnyException();

        verify(outboxService).markDeadLettered(9L);
        assertThat(output).contains("ERROR")
                .contains("Unreadable order.placed message dead-lettered from subscription ?: MessageFormatException")
                .doesNotContain("SECRET-PROPERTY");
    }

    @Test
    void unreadableOutboxIdProperty_isTreatedAsNoOutboxRecord() throws Exception {
        TextMessage message = deadLetter("order-placed-email", "{}");
        when(message.propertyExists(MessageTypes.OUTBOX_ID_PROPERTY)).thenReturn(true);
        when(message.getLongProperty(MessageTypes.OUTBOX_ID_PROPERTY)).thenThrow(new MessageFormatException("bytes"));
        when(converter.fromMessage(message)).thenReturn(new OrderPlacedMessage(42L, "A", "a@example.com", null, "HN",
                null, BigDecimal.TEN, null, List.of()));

        listener.onDeadLetter(message);

        verifyNoInteractions(outboxService);
    }

    @Test
    void databaseErrorWhileMarkingOutboxRecord_isRethrown_forRedelivery() throws Exception {
        TextMessage message = deadLetter("order-placed-email", "{}");
        withOutboxId(message, 9L);
        when(converter.fromMessage(message)).thenThrow(new MessageConversionException("Unknown alias"));
        when(outboxService.markDeadLettered(9L)).thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> listener.onDeadLetter(message)).isInstanceOf(QueryTimeoutException.class);
    }

    private static TextMessage deadLetter(String originalQueue, String body) throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(OrderPlacedDlqListener.ORIGINAL_QUEUE_PROPERTY)).thenReturn(originalQueue);
        when(message.getText()).thenReturn(body);
        return message;
    }

    private static void withOutboxId(TextMessage message, long outboxId) throws Exception {
        when(message.propertyExists(MessageTypes.OUTBOX_ID_PROPERTY)).thenReturn(true);
        when(message.getLongProperty(MessageTypes.OUTBOX_ID_PROPERTY)).thenReturn(outboxId);
    }
}
