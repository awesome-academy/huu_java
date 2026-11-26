package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.service.messaging.Destinations;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.Test;
import org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cấu hình JMS chạy trên broker embedded thật (không cần Docker): template queue/topic, topic với shared durable
 * subscription, retry + DLQ theo {@code artemis/broker.properties}, và cặp {@code mode}/{@code embedded.enabled}.
 */
class JmsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ArtemisAutoConfiguration.class,
                    JmsAutoConfiguration.class))
            .withUserConfiguration(JmsConfig.class, ArtemisEmbeddedConfig.class)
            .withPropertyValues(
                    "spring.artemis.mode=embedded",
                    "spring.artemis.embedded.enabled=true",
                    "spring.jms.listener.session.transacted=true",
                    "app.jms.embedded-broker-properties[0]=classpath:artemis/broker.properties",
                    "app.jms.embedded-broker-properties[1]=classpath:artemis/broker-test.properties");

    @Test
    void templates_queueIsPrimary_topicTemplateIsPubSub() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JmsTemplate.class).isPubSubDomain()).isFalse();
            assertThat(context.getBean(JmsConfig.TOPIC_JMS_TEMPLATE, JmsTemplate.class).isPubSubDomain()).isTrue();
            assertThat(context.getBean(JmsTemplate.class).getMessageConverter())
                    .isInstanceOf(AliasOnlyJsonMessageConverter.class);
        });
    }

    @Test
    void queue_sendAndReceive() {
        runner.run(context -> {
            context.getBean(JmsTemplate.class).send("test.queue", session -> session.createTextMessage("hello"));

            Message received = receiver(context).receive("test.queue");

            assertThat(received).isInstanceOf(TextMessage.class);
            assertThat(((TextMessage) received).getText()).isEqualTo("hello");
        });
    }

    @Test
    void topic_eachSubscriptionGetsItsOwnCopy_andFailingOneIsRetriedThenDeadLettered() {
        runner.withUserConfiguration(TopicListeners.class).run(context -> {
            TopicListeners listeners = context.getBean(TopicListeners.class);
            awaitSubscriptionsRegistered(context);

            context.getBean(JmsConfig.TOPIC_JMS_TEMPLATE, JmsTemplate.class)
                    .send(Destinations.ORDER_PLACED, session -> session.createTextMessage("order-1"));

            // Subscription lỗi: giao đúng maxDeliveryAttempts (5) lần rồi vào DLQ riêng
            Message deadLettered = receiver(context).receive(Destinations.ORDER_PLACED_DLQ);
            assertThat(deadLettered).isNotNull();
            assertThat(((TextMessage) deadLettered).getText()).isEqualTo("order-1");
            assertThat(deadLettered.getStringProperty("_AMQ_ORIG_QUEUE")).contains("test-failing");
            assertThat(listeners.failingAttempts).hasValue(5);
            // Subscription còn lại nhận đúng 1 lần, không bị giao lại vì subscription kia lỗi
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(listeners.received).containsExactly("order-1"));
        });
    }

    @Test
    void topic_messageSentBeforeAnyListenerRegistered_isKeptForEverySubscription() {
        // Không có listener nào trong context này: queue của subscription phải do broker.properties tạo sẵn
        runner.run(context -> {
            context.getBean(JmsConfig.TOPIC_JMS_TEMPLATE, JmsTemplate.class)
                    .send(Destinations.ORDER_PLACED, session -> session.createTextMessage("early-order"));

            for (String subscription : List.of(Destinations.ORDER_PLACED_EMAIL_SUBSCRIPTION,
                    Destinations.ORDER_PLACED_SLACK_SUBSCRIPTION)) {
                // FQQN address::queue — đọc thẳng hàng đợi của subscription
                Message kept = receiver(context).receive(Destinations.ORDER_PLACED + "::" + subscription);
                assertThat(kept).as(subscription).isInstanceOf(TextMessage.class);
                assertThat(((TextMessage) kept).getText()).isEqualTo("early-order");
            }
        });
    }

    @Test
    void broadcastTopic_everyInstanceGetsACopy_andFailuresAreNotRedelivered() {
        // Hai listener trên factory broadcast = hai instance của app: subscription non-durable không shared mỗi bên
        runner.withUserConfiguration(BroadcastListeners.class).run(context -> {
            BroadcastListeners listeners = context.getBean(BroadcastListeners.class);
            awaitSubscriptionsRegistered(context);

            context.getBean(JmsConfig.TOPIC_JMS_TEMPLATE, JmsTemplate.class)
                    .send(Destinations.ORDER_PLACED, session -> session.createTextMessage("order-2"));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(listeners.instanceA).containsExactly("order-2");
                assertThat(listeners.instanceB).containsExactly("order-2");
                assertThat(listeners.failingAttempts).hasValue(1);
            });
            // Không transacted: lỗi không làm broker giao lại (redelivery delay của test là 100ms)
            Thread.sleep(500);
            assertThat(listeners.failingAttempts).hasValue(1);
        });
    }

    @Test
    void broadcastFactory_isNonDurableNonSharedAndNotTransacted() {
        runner.withUserConfiguration(BroadcastListeners.class).run(context -> {
            awaitSubscriptionsRegistered(context);
            DefaultMessageListenerContainer container = (DefaultMessageListenerContainer) context
                    .getBean(JmsListenerEndpointRegistry.class).getListenerContainer(BroadcastListeners.INSTANCE_A);
            assertThat(container.isPubSubDomain()).isTrue();
            assertThat(container.isSubscriptionDurable()).isFalse();
            assertThat(container.isSubscriptionShared()).isFalse();
            assertThat(container.isSessionTransacted()).isFalse();
            assertThat(container.getMaxConcurrentConsumers()).isEqualTo(1);
        });
    }

    @Test
    void nativeMode_withEmbeddedBrokerStillEnabled_failsStartup() {
        runner.withPropertyValues("spring.artemis.mode=native").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause().hasMessageContaining("spring.artemis.embedded.enabled=false");
        });
    }

    @Test
    void nativeMode_withEmbeddedBrokerDisabled_startsNoEmbeddedBroker() {
        runner.withPropertyValues("spring.artemis.mode=native", "spring.artemis.embedded.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmbeddedActiveMQ.class);
                });
    }

    /** {@code JmsTemplate} có receive timeout ({@code JmsTemplate} của app chờ vô hạn). */
    private static JmsTemplate receiver(AssertableApplicationContext context) {
        JmsTemplate receiver = new JmsTemplate(context.getBean(ConnectionFactory.class));
        receiver.setReceiveTimeout(Duration.ofSeconds(10).toMillis());
        return receiver;
    }

    /** Subscription chỉ tồn tại khi consumer đã đăng ký: gửi trước đó thì topic không có ai nhận, message mất. */
    private static void awaitSubscriptionsRegistered(AssertableApplicationContext context) {
        JmsListenerEndpointRegistry registry = context.getBean(JmsListenerEndpointRegistry.class);
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.getListenerContainers().stream()
                .allMatch(container -> ((DefaultMessageListenerContainer) container).isRegisteredWithDestination()));
    }

    @Configuration(proxyBeanMethods = false)
    static class TopicListeners {

        final AtomicInteger failingAttempts = new AtomicInteger();
        final List<String> received = new CopyOnWriteArrayList<>();

        @JmsListener(destination = Destinations.ORDER_PLACED, subscription = "test-failing",
                containerFactory = JmsConfig.TOPIC_FACTORY)
        void failing(Message message) {
            failingAttempts.incrementAndGet();
            throw new IllegalStateException("simulated failure");
        }

        @JmsListener(destination = Destinations.ORDER_PLACED, subscription = "test-ok",
                containerFactory = JmsConfig.TOPIC_FACTORY)
        void ok(TextMessage message) throws JMSException {
            received.add(message.getText());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BroadcastListeners {

        static final String INSTANCE_A = "broadcast-a";

        final List<String> instanceA = new CopyOnWriteArrayList<>();
        final List<String> instanceB = new CopyOnWriteArrayList<>();
        final AtomicInteger failingAttempts = new AtomicInteger();

        @JmsListener(id = INSTANCE_A, destination = Destinations.ORDER_PLACED,
                containerFactory = JmsConfig.TOPIC_BROADCAST_FACTORY)
        void a(TextMessage message) throws JMSException {
            instanceA.add(message.getText());
        }

        @JmsListener(destination = Destinations.ORDER_PLACED, containerFactory = JmsConfig.TOPIC_BROADCAST_FACTORY)
        void b(TextMessage message) throws JMSException {
            instanceB.add(message.getText());
        }

        @JmsListener(destination = Destinations.ORDER_PLACED, containerFactory = JmsConfig.TOPIC_BROADCAST_FACTORY)
        void failing(Message message) {
            failingAttempts.incrementAndGet();
            throw new IllegalStateException("simulated failure");
        }
    }
}
