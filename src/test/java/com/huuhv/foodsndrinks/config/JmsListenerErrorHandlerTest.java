package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;
import org.springframework.jms.support.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class JmsListenerErrorHandlerTest {

    private final JmsListenerErrorHandler handler = new JmsListenerErrorHandler();

    @Test
    void conversionError_loggedAsSingleWarnLine_withoutStackTrace(CapturedOutput output) {
        handler.handleError(new ListenerExecutionFailedException("Listener method could not be invoked",
                new MessageConversionException("Cannot convert JMS message ID:1 (JacksonException)")));

        assertThat(output).contains("WARN").contains("Cannot convert JMS message ID:1 (JacksonException)")
                .doesNotContain("\tat ");
    }

    @Test
    void otherError_loggedAsErrorWithStackTrace(CapturedOutput output) {
        handler.handleError(new ListenerExecutionFailedException("Listener method failed",
                new IllegalStateException("smtp down")));

        assertThat(output).contains("ERROR").contains("smtp down").contains("\tat ");
    }
}
