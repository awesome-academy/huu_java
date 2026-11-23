package com.huuhv.foodsndrinks.service.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.MessageCreator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Transactional Outbox trên MySQL thật (Testcontainers — {@code FOR UPDATE SKIP LOCKED}) + broker embedded. Broker lỗi
 * được giả lập bằng spy của template topic ném {@code JmsException}. Job tự động bị tắt ở profile test
 * ({@code app.outbox.poll-interval: 24h}); test gọi {@link OutboxPublisher#publishDue()} như một chu kỳ của job.
 */
class OutboxIntegrationTest extends AbstractMessagingIntegrationTest {

    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void enqueue_writesOneRowInsideTheCallersTransaction() {
        long orderId = newOrderId();

        Integer rowsSeenInTransaction = new TransactionTemplate(transactionManager).execute(status -> {
            outboxService.enqueue(OutboxType.ORDER_PLACED, message(orderId));
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?",
                    Integer.class, orderId);
        });

        assertThat(rowsSeenInTransaction).isEqualTo(1);
        // Gửi ngay sau commit (không chờ job)
        await().atMost(WAIT).until(() -> "SENT".equals(outboxStatusOf(orderId)));
        verify(slackNotificationService, timeout(WAIT.toMillis())).sendOrderPlaced(order(orderId));
    }

    @Test
    void enqueue_withoutTransaction_isRejected() {
        assertThatThrownBy(() -> outboxService.enqueue(OutboxType.ORDER_PLACED, message(newOrderId())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void brokerDownAtCommit_orderStaysPending_thenRelaySendsItOnce() {
        long orderId = newOrderId();
        brokerDown();

        placeOrder(orderId, false);

        await().atMost(WAIT).until(() -> attemptsOf(orderId) == 1);
        assertThat(outboxStatusOf(orderId)).isEqualTo("PENDING");
        verify(slackNotificationService, never()).sendOrderPlaced(order(orderId));

        brokerUp();
        assertThat(outboxPublisher.publishDue()).isGreaterThanOrEqualTo(1);

        assertThat(outboxStatusOf(orderId)).isEqualTo("SENT");
        verify(slackNotificationService, timeout(WAIT.toMillis())).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, after(1000).times(1)).sendNewOrderEmail(order(orderId));
    }

    @Test
    void fiveFailedAttempts_markFailed_withErrorLog_andManualResetResendsOnce(CapturedOutput output) {
        long orderId = newOrderId();
        brokerDown();
        placeOrder(orderId, false);
        await().atMost(WAIT).until(() -> attemptsOf(orderId) == 1);   // lần gửi ngay sau commit

        for (int attempt = 2; attempt <= 5; attempt++) {
            outboxPublisher.publishDue();
        }

        assertThat(outboxStatusOf(orderId)).isEqualTo("FAILED");
        assertThat(attemptsOf(orderId)).isEqualTo(5);
        assertThat(output.getOut()).contains("ERROR")
                .contains("Outbox message #" + outboxIdOf(orderId) + " (orderPlaced) FAILED after 5 attempts");
        assertThat(outboxPublisher.publishDue()).isZero();            // FAILED không được thử nữa
        assertThat(attemptsOf(orderId)).isEqualTo(5);

        // Khắc phục broker rồi đặt lại bằng tay (README) → gửi được, mỗi kênh đúng một lần
        brokerUp();
        jdbcTemplate.update("UPDATE outbox_messages SET status = 'PENDING', attempts = 0 WHERE id = ?",
                outboxIdOf(orderId));
        outboxPublisher.publishDue();

        assertThat(outboxStatusOf(orderId)).isEqualTo("SENT");
        verify(slackNotificationService, timeout(WAIT.toMillis()).times(1)).sendOrderPlaced(order(orderId));
        verify(emailNotificationService, after(1000).times(1)).sendNewOrderEmail(order(orderId));
    }

    @Test
    void concurrentRelays_neverSendTheSameRecordTwice() throws Exception {
        int count = 20;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long orderId = newOrderId();
            ids.add(orderId);
            // Chèn thẳng (không qua enqueue) để không có lần gửi ngay sau commit — chỉ hai relay bên dưới gửi
            jdbcTemplate.update("INSERT INTO outbox_messages (type, payload) VALUES (?, ?)",
                    OutboxType.ORDER_PLACED.alias(), jsonMapper.writeValueAsString(message(orderId)));
        }
        clearInvocations(topicJmsTemplate);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> relay = () -> {
            start.await();
            return outboxPublisher.publishDue();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(relay);
            Future<Integer> second = pool.submit(relay);
            start.countDown();

            assertThat(first.get() + second.get()).isEqualTo(count);
        } finally {
            pool.shutdownNow();
        }

        verify(topicJmsTemplate, times(count)).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));
        assertThat(ids).allSatisfy(orderId -> assertThat(outboxStatusOf(orderId)).isEqualTo("SENT"));
    }

    // -------------------------------------------------------

    private void brokerDown() {
        doThrow(new UncategorizedJmsException("simulated broker outage"))
                .when(topicJmsTemplate).send(eq(Destinations.ORDER_PLACED), any(MessageCreator.class));
    }

    private void brokerUp() {
        reset(topicJmsTemplate);   // spy: bỏ stub → gọi phương thức thật
    }

    private int attemptsOf(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT attempts FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.orderId') = ?", Integer.class,
                orderId).stream().findFirst().orElse(-1);
    }
}
