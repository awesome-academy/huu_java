package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.service.messaging.MessageTypes;
import jakarta.jms.JMSException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jms.support.converter.MessageConversionException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliasOnlyJsonMessageConverterTest {

    record Sample(Long id, BigDecimal amount) {
    }

    record NotRegistered(String value) {
    }

    private final AliasOnlyJsonMessageConverter converter =
            new AliasOnlyJsonMessageConverter(JsonMapper.builder().build(), Map.of("sample", Sample.class));

    @Test
    void toMessage_writesJsonTextMessage_withAliasNotClassName() throws JMSException {
        Session session = mock(Session.class);
        TextMessage message = mock(TextMessage.class);
        when(session.createTextMessage(anyString())).thenReturn(message);

        converter.toMessage(new Sample(7L, new BigDecimal("12.50")), session);

        verify(session).createTextMessage("{\"id\":7,\"amount\":12.50}");
        verify(message).setStringProperty(MessageTypes.TYPE_ID_PROPERTY, "sample");
    }

    @Test
    void toMessage_rejectsClassWithoutAlias() {
        Session session = mock(Session.class);

        assertThatThrownBy(() -> converter.toMessage(new NotRegistered("x"), session))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageContaining("MessageTypes.TYPE_ID_MAPPINGS");
    }

    @Test
    void fromMessage_readsRegisteredAlias() throws JMSException {
        TextMessage message = textMessage("sample", "{\"id\":7,\"amount\":12.50}");

        assertThat(converter.fromMessage(message)).isEqualTo(new Sample(7L, new BigDecimal("12.50")));
    }

    @Test
    void fromMessage_rejectsFullyQualifiedClassName_withoutLoadingIt() throws JMSException {
        TextMessage message = textMessage(Sample.class.getName(), "{\"id\":7}");

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageNotContaining(Sample.class.getName())
                .hasNoCause();
    }

    @Test
    void fromMessage_rejectsMissingAlias() throws JMSException {
        TextMessage message = textMessage(null, "{\"id\":7}");

        assertThatThrownBy(() -> converter.fromMessage(message)).isInstanceOf(MessageConversionException.class);
    }

    @Test
    void conversionError_doesNotLeakSenderData_intoExceptionMessage() throws JMSException {
        // Alias lạ có CR/LF (giả dòng log) và payload JSON hỏng: thông điệp lỗi gốc của Jackson nối nguyên các chuỗi này
        TextMessage unknownAlias = textMessage("evil\r\n2026-01-01 INFO fake log line", "{}");
        TextMessage brokenJson = textMessage("sample", "{\"id\":\"SECRET-PAYLOAD\r\nINJECTED\"");

        assertThatThrownBy(() -> converter.fromMessage(unknownAlias))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageNotContaining("fake log line")
                .hasNoCause();
        assertThatThrownBy(() -> converter.fromMessage(brokenJson))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageNotContaining("SECRET-PAYLOAD")
                .hasMessageNotContaining("INJECTED")
                .hasNoCause();
    }

    @Test
    void typePropertyOfWrongType_isConversionError_notJmsException() throws JMSException {
        // Bên gửi đặt _type kiểu bytes: client JMS ném MessageFormatException (checked). Để thoát nguyên dạng thì Spring
        // bọc thành "Failed to extract payload from [<message kèm mọi property>]" và DLQ listener giao lại mãi
        TextMessage message = textMessage("ignored", "{}");
        when(message.getStringProperty(MessageTypes.TYPE_ID_PROPERTY))
                .thenThrow(new MessageFormatException("Invalid conversion SECRET-PROPERTY"));

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageContaining("MessageFormatException")
                .hasMessageNotContaining("SECRET-PROPERTY")
                .hasNoCause();
    }

    @Test
    void messageId_inErrors_isStrippedOfLineBreaks_andTruncated() throws JMSException {
        TextMessage message = textMessage("unknown", "{}");
        when(message.getJMSMessageID()).thenReturn("ID:abc\nforged" + "x".repeat(500));

        assertThatThrownBy(() -> converter.fromMessage(message))
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain("\n").contains("ID:abc_forged");
                    assertThat(ex.getMessage().length()).isLessThan(300);
                });
    }

    @Test
    void constructor_rejectsTwoAliasesForOneClass() {
        assertThatThrownBy(() -> new AliasOnlyJsonMessageConverter(JsonMapper.builder().build(),
                Map.of("a", Sample.class, "b", Sample.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TextMessage textMessage(String alias, String body) throws JMSException {
        TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty(MessageTypes.TYPE_ID_PROPERTY)).thenReturn(alias);
        when(message.getText()).thenReturn(body);
        when(message.getJMSMessageID()).thenReturn("ID:test-1");
        return message;
    }
}
