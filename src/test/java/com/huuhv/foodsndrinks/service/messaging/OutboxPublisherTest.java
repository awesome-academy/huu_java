package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboxPublisherTest {

    @Mock private OutboxMessageRepository repository;
    @Mock private JmsTemplate queueJmsTemplate;
    @Mock private JmsTemplate topicJmsTemplate;
    @Mock private PlatformTransactionManager transactionManager;

    private final OutboxProperties properties =
            new OutboxProperties(Duration.ofMinutes(1), Duration.ofSeconds(30), 5, 3, Duration.ofDays(7));
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, queueJmsTemplate, topicJmsTemplate, properties, transactionManager);
    }

    @Test
    void publish_sendsPayloadToTopic_andMarksSent() {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 0)));

        assertThat(publisher.publish(7L)).isTrue();

        verify(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));
        verify(repository).markSent(7L);
        verifyNoInteractions(queueJmsTemplate);
    }

    @Test
    void publish_alreadySentOrLockedElsewhere_doesNothing() {
        when(repository.lockDueById(7L)).thenReturn(Optional.empty());

        assertThat(publisher.publish(7L)).isFalse();

        verifyNoInteractions(topicJmsTemplate);
    }

    @Test
    void brokerError_recordsFailedAttempt_withRetryDelay_andWarns(CapturedOutput output) {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 1)));
        brokerDown();

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository).markAttemptFailed(7L, 5, 30_000L, "UncategorizedJmsException");
        verify(repository, never()).markSent(anyLong());
        assertThat(output).contains("WARN").contains("Outbox message #7 (orderPlaced) send attempt 2/5 failed");
    }

    @Test
    void lastAttempt_isLoggedAsError(CapturedOutput output) {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 4)));
        brokerDown();

        publisher.publish(7L);

        assertThat(output).contains("ERROR").contains("Outbox message #7 (orderPlaced) FAILED after 5 attempts");
    }

    @Test
    void unknownType_isRecordedAsFailure_notSent() {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "renamedType", 0)));

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository).markAttemptFailed(eq(7L), anyInt(), anyLong(), eq("IllegalStateException"));
        verifyNoInteractions(topicJmsTemplate, queueJmsTemplate);
    }

    @Test
    void publishDue_sendsUpToBatchSize() {
        when(repository.lockNextDue()).thenReturn(Optional.of(row(1L, "orderPlaced", 0)),
                Optional.of(row(2L, "orderPlaced", 0)), Optional.of(row(3L, "orderPlaced", 0)),
                Optional.of(row(4L, "orderPlaced", 0)));

        assertThat(publisher.publishDue()).isEqualTo(3);           // batch-size = 3

        verify(repository, times(3)).markSent(anyLong());
    }

    @Test
    void publishDue_stopsAtFirstBrokerError_soOtherRecordsKeepTheirAttempts() {
        when(repository.lockNextDue()).thenReturn(Optional.of(row(1L, "orderPlaced", 0)),
                Optional.of(row(2L, "orderPlaced", 0)));
        brokerDown();

        assertThat(publisher.publishDue()).isZero();

        verify(repository, times(1)).lockNextDue();
        verify(repository).markAttemptFailed(eq(1L), anyInt(), anyLong(), anyString());
    }

    private void brokerDown() {
        doThrow(new UncategorizedJmsException("connection refused"))
                .when(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));
    }

    private static OutboxMessage row(long id, String type, int attempts) {
        return OutboxMessage.builder().id(id).type(type).payload("{\"orderId\":" + id + "}").attempts(attempts).build();
    }
}
