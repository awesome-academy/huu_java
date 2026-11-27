package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import com.huuhv.foodsndrinks.entity.OutboxMessage;
import com.huuhv.foodsndrinks.repository.OutboxMessageRepository;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.ActiveMQAddressFullException;
import org.apache.activemq.artemis.api.core.ActiveMQConnectionTimedOutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
    @Mock private JmsBrokerProbe brokerProbe;

    private final OutboxProperties properties =
            new OutboxProperties(Duration.ofMinutes(1), Duration.ofSeconds(30), 5, 3, Duration.ofDays(7),
                    Duration.ofHours(1));
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, queueJmsTemplate, topicJmsTemplate, properties, transactionManager,
                brokerProbe);
        lenient().when(brokerProbe.reachable()).thenReturn(true);
    }

    @Test
    void publish_sendsPayloadToTopic_withTypeAndOutboxId_andMarksSent_inReadCommittedTransaction() throws Exception {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 0)));

        assertThat(publisher.publish(7L)).isTrue();

        ArgumentCaptor<MessageCreator> creator = ArgumentCaptor.forClass(MessageCreator.class);
        verify(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), creator.capture());
        Session session = mock(Session.class);
        TextMessage jms = mock(TextMessage.class);
        when(session.createTextMessage("{\"orderId\":7}")).thenReturn(jms);
        creator.getValue().createMessage(session);
        verify(jms).setStringProperty(MessageTypes.TYPE_ID_PROPERTY, "orderPlaced");
        verify(jms).setLongProperty(MessageTypes.OUTBOX_ID_PROPERTY, 7L);
        verify(repository).markSent(7L);
        verifyNoInteractions(queueJmsTemplate);
        // READ COMMITTED: câu khoá theo khoảng không giữ gap lock chặn INSERT outbox của checkout
        verify(transactionManager).getTransaction(argThat(definition ->
                definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
    }

    @Test
    void publish_queueType_isSentWithTheQueueTemplate_notPublishedToATopic() throws Exception {
        // excelImport là loại point-to-point: gửi bằng template topic thì message tới một address MULTICAST không ai nghe
        when(repository.lockDueById(8L)).thenReturn(Optional.of(row(8L, MessageTypes.EXCEL_IMPORT, 0)));

        assertThat(publisher.publish(8L)).isTrue();

        ArgumentCaptor<MessageCreator> creator = ArgumentCaptor.forClass(MessageCreator.class);
        verify(queueJmsTemplate).send(eq(Destinations.EXCEL_IMPORT), creator.capture());
        Session session = mock(Session.class);
        TextMessage jms = mock(TextMessage.class);
        when(session.createTextMessage("{\"orderId\":8}")).thenReturn(jms);
        creator.getValue().createMessage(session);
        verify(jms).setStringProperty(MessageTypes.TYPE_ID_PROPERTY, MessageTypes.EXCEL_IMPORT);
        verify(jms).setLongProperty(MessageTypes.OUTBOX_ID_PROPERTY, 8L);
        verify(repository).markSent(8L);
        verifyNoInteractions(topicJmsTemplate);
    }

    @Test
    void publish_alreadySentOrLockedElsewhere_doesNothing() {
        when(repository.lockDueById(7L)).thenReturn(Optional.empty());

        assertThat(publisher.publish(7L)).isFalse();

        verifyNoInteractions(topicJmsTemplate);
    }

    @Test
    void brokerUnreachable_doesNotCountAnAttempt_recordStaysPending(CapturedOutput output) {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 0)));
        sendFails();
        brokerUnreachable();

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository, never()).markAttemptFailed(anyLong(), anyInt(), anyLong(), anyString());
        verify(repository, never()).markSent(anyLong());
        assertThat(output).contains("JMS broker unavailable (UncategorizedJmsException), outbox message #7 stays PENDING");
    }

    @Test
    void sendTimeout_isBrokerFailure_evenIfANewConnectionSucceeds() {
        // Broker treo / chặn producer: kết nối mới vẫn mở được nhưng gửi hết callTimeout → không phải lỗi của bản ghi
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 0)));
        JMSException timedOut = new JMSException("AMQ219014: Timed out after waiting 10000 ms");
        timedOut.initCause(new ActiveMQConnectionTimedOutException("timed out"));
        doThrow(new UncategorizedJmsException(timedOut))
                .when(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository, never()).markAttemptFailed(anyLong(), anyInt(), anyLong(), anyString());
        verify(brokerProbe, never()).reachable();
    }

    @Test
    void isBrokerFailure_recognisesArtemisConnectionErrorsInTheCauseChain() {
        JMSException addressFull = new JMSException("full");
        addressFull.initCause(new ActiveMQAddressFullException("address full"));

        assertThat(OutboxPublisher.isBrokerFailure(new UncategorizedJmsException(addressFull))).isTrue();
        assertThat(OutboxPublisher.isBrokerFailure(new UncategorizedJmsException(new JMSException("x")))).isFalse();
        assertThat(OutboxPublisher.isBrokerFailure(new UncategorizedJmsException("rejected"))).isFalse();
    }

    @Test
    void sendErrorWhileBrokerReachable_countsAnAttempt_withRetryDelay(CapturedOutput output) {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 1)));
        sendFails();

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository).markAttemptFailed(7L, 5, 30_000L, "UncategorizedJmsException");
        assertThat(output).contains("WARN").contains("Outbox message #7 (orderPlaced) send attempt 2/5 failed");
    }

    @Test
    void lastAttempt_isLoggedAsError(CapturedOutput output) {
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "orderPlaced", 4)));
        sendFails();

        publisher.publish(7L);

        assertThat(output).contains("ERROR").contains("Outbox message #7 (orderPlaced) FAILED after 5 attempts");
    }

    @Test
    void unknownType_isPostponed_withoutCountingAnAttempt(CapturedOutput output) {
        // Rolling deploy: bản ghi do phiên bản mới ghi, instance cũ chưa biết alias → để instance mới gửi
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "typeFromNewerVersion", 4)));
        when(repository.postpone(eq(7L), anyLong(), anyLong(), anyString())).thenReturn(1);

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository).postpone(7L, 30_000L, 3600L, OutboxPublisher.UNKNOWN_TYPE_ERROR);
        verify(repository, never()).markAttemptFailed(anyLong(), anyInt(), anyLong(), anyString());
        verifyNoInteractions(topicJmsTemplate, queueJmsTemplate);
        assertThat(output).contains("WARN").contains("typeFromNewerVersion").doesNotContain("FAILED");
    }

    @Test
    void unknownType_pastGracePeriod_countsAttempts_untilFailed(CapturedOutput output) {
        // Không instance nào biết loại này (rollback bản deploy): không hoãn mãi, đi đường lỗi của bản ghi
        when(repository.lockDueById(7L)).thenReturn(Optional.of(row(7L, "typeFromRolledBackVersion", 4)));
        when(repository.postpone(eq(7L), anyLong(), anyLong(), anyString())).thenReturn(0);

        assertThat(publisher.publish(7L)).isFalse();

        verify(repository).markAttemptFailed(7L, 5, 30_000L, OutboxPublisher.UNKNOWN_TYPE_ERROR);
        verifyNoInteractions(topicJmsTemplate, queueJmsTemplate);
        assertThat(output).contains("ERROR")
                .contains("Outbox message #7 (typeFromRolledBackVersion) FAILED after 5 attempts");
    }

    @Test
    void publishDue_skipsUnknownType_andContinuesWithNextRecord() {
        when(repository.lockNextDueAfter(0L)).thenReturn(Optional.of(row(1L, "typeFromNewerVersion", 0)));
        when(repository.lockNextDueAfter(1L)).thenReturn(Optional.of(row(2L, "orderPlaced", 0)));
        when(repository.postpone(eq(1L), anyLong(), anyLong(), anyString())).thenReturn(1);

        assertThat(publisher.publishDue()).isEqualTo(1);

        verify(repository).postpone(eq(1L), anyLong(), anyLong(), anyString());
        verify(repository).markSent(2L);
    }

    @Test
    void publishDue_sendsUpToBatchSize_walkingIdsForward() {
        when(repository.lockNextDueAfter(0L)).thenReturn(Optional.of(row(1L, "orderPlaced", 0)));
        when(repository.lockNextDueAfter(1L)).thenReturn(Optional.of(row(2L, "orderPlaced", 0)));
        when(repository.lockNextDueAfter(2L)).thenReturn(Optional.of(row(3L, "orderPlaced", 0)));

        assertThat(publisher.publishDue()).isEqualTo(3);           // batch-size = 3

        verify(repository, times(3)).markSent(anyLong());
        verify(repository, never()).lockNextDueAfter(3L);
    }

    @Test
    void publishDue_brokenRecord_doesNotBlockTheNextOnes() {
        when(repository.lockNextDueAfter(0L)).thenReturn(Optional.of(row(1L, "orderPlaced", 0)));
        when(repository.lockNextDueAfter(1L)).thenReturn(Optional.of(row(2L, "orderPlaced", 0)));
        when(repository.lockNextDueAfter(2L)).thenReturn(Optional.empty());
        // Bản ghi 1 bị broker từ chối (broker vẫn sống) — lỗi riêng của bản ghi; bản ghi 2 gửi được
        doThrow(new UncategorizedJmsException("message rejected")).doNothing()
                .when(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));

        assertThat(publisher.publishDue()).isEqualTo(1);

        verify(repository).markAttemptFailed(eq(1L), anyInt(), anyLong(), anyString());
        verify(repository).markSent(2L);
    }

    @Test
    void publishDue_stopsWhenBrokerIsUnreachable_withoutCountingAttempts() {
        when(repository.lockNextDueAfter(0L)).thenReturn(Optional.of(row(1L, "orderPlaced", 0)));
        sendFails();
        brokerUnreachable();

        assertThat(publisher.publishDue()).isZero();

        verify(repository, times(1)).lockNextDueAfter(anyLong());
        verify(repository, never()).markAttemptFailed(anyLong(), anyInt(), anyLong(), anyString());
    }

    private void sendFails() {
        doThrow(new UncategorizedJmsException("send failed"))
                .when(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));
    }

    private void brokerUnreachable() {
        when(brokerProbe.reachable()).thenReturn(false);
    }

    private static OutboxMessage row(long id, String type, int attempts) {
        return OutboxMessage.builder().id(id).type(type).payload("{\"orderId\":" + id + "}").attempts(attempts).build();
    }
}
