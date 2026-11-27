package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import com.huuhv.foodsndrinks.service.messaging.MessageTypes;
import com.huuhv.foodsndrinks.service.websocket.RealtimeSubscriptionRestoredEvent;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JMS (ActiveMQ Artemis). Kết nối/broker do Spring Boot cấu hình từ {@code spring.artemis.*} (broker embedded ở dev/test,
 * broker riêng ở compose/prod — {@link ArtemisEmbeddedConfig}); ở đây chỉ khai báo phần Boot không tự làm:
 * <ul>
 *   <li>{@link AliasOnlyJsonMessageConverter}: JSON + whitelist alias ({@code MessageTypes}). Boot gắn bean
 *       {@link MessageConverter} vào listener factory qua configurer; hai {@link JmsTemplate} bên dưới gắn tay.</li>
 *   <li>Listener factory: Boot 4 không gắn {@code ErrorHandler} → tự khai báo (vẫn qua
 *       {@link DefaultJmsListenerContainerFactoryConfigurer} để giữ mọi {@code spring.jms.listener.*}, và bỏ lớp cache
 *       connection như Boot — container tự quản lý connection/consumer). {@code spring.jms.listener.session.transacted}
 *       = true: listener ném exception → rollback → broker giao lại, hết số lần thì vào DLQ
 *       ({@code artemis/broker.properties}).</li>
 *   <li>{@link #TOPIC_FACTORY}: topic, shared durable subscription — mỗi {@code subscription} là một hàng đợi riêng trên
 *       broker (consumer này lỗi không làm consumer khác nhận lại), nhận cả message gửi lúc app đang tắt, và nhiều
 *       instance cùng subscription chia nhau message (không cần clientId).</li>
 *   <li>{@link #TOPIC_BROADCAST_FACTORY}: topic, subscription non-durable không shared — mỗi instance nhận mọi message
 *       (sự kiện realtime cho WebSocket), không giao lại khi lỗi.</li>
 *   <li>Hai {@link JmsTemplate}: Boot chỉ tạo template khi chưa có bean {@code JmsOperations} nào, nên khai báo cả
 *       {@code jmsTemplate} (queue, {@code @Primary}) lẫn {@link #TOPIC_JMS_TEMPLATE}. {@code spring.jms.template.*}
 *       không có tác dụng.</li>
 * </ul>
 */
@Configuration
@EnableJms
@EnableConfigurationProperties(OutboxProperties.class)
public class JmsConfig {

    /** Tên bean: hằng số nằm ở {@link JmsBrokerProperties} để service dùng mà không import {@code config}. */
    public static final String TOPIC_FACTORY = JmsBrokerProperties.TOPIC_LISTENER_FACTORY;
    public static final String TOPIC_JMS_TEMPLATE = JmsBrokerProperties.TOPIC_JMS_TEMPLATE;
    public static final String TOPIC_BROADCAST_FACTORY = JmsBrokerProperties.TOPIC_BROADCAST_LISTENER_FACTORY;

    @Bean
    public MessageConverter jmsMessageConverter(JsonMapper jsonMapper) {
        return new AliasOnlyJsonMessageConverter(jsonMapper, MessageTypes.TYPE_ID_MAPPINGS);
    }

    @Bean
    public JmsListenerErrorHandler jmsListenerErrorHandler() {
        return new JmsListenerErrorHandler();
    }

    /** Queue (point-to-point) — factory mặc định của {@code @JmsListener}, thay bean cùng tên của Boot. */
    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            JmsListenerErrorHandler errorHandler) {
        return listenerFactory(configurer, connectionFactory, errorHandler);
    }

    /** Topic (publish/subscribe) với shared durable subscription. */
    @Bean(name = TOPIC_FACTORY)
    public DefaultJmsListenerContainerFactory topicListenerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            JmsListenerErrorHandler errorHandler) {
        DefaultJmsListenerContainerFactory factory = listenerFactory(configurer, connectionFactory, errorHandler);
        factory.setPubSubDomain(true);
        factory.setSubscriptionDurable(true);
        factory.setSubscriptionShared(true);
        // Một thread mỗi subscription (ghi đè spring.jms.listener.*-concurrency): consumer thông báo giữ một connection DB
        // suốt lúc gọi Slack/SMTP (NotificationLogService) — thông báo admin không cần song song, mỗi thread thêm là một
        // connection Hikari bị chiếm khi SMTP chậm (ConnectionBudgetCheck). Nhiều instance vẫn chia nhau message.
        factory.setConcurrency("1");
        return factory;
    }

    /**
     * Topic với subscription <b>non-durable, không shared</b>: mỗi instance nhận một bản của mọi message — dùng cho sự kiện
     * realtime mà instance nào cũng phải đẩy tới client WebSocket đang nối vào nó (simple broker STOMP nằm trong bộ nhớ
     * từng instance). Instance tắt thì subscription (queue tạm trên broker) biến mất, không dồn message cũ.
     * <p>
     * Không transacted, {@code AUTO_ACKNOWLEDGE}: với {@code DefaultMessageListenerContainer} message được ack trước khi
     * listener chạy — lỗi không làm broker giao lại/đẩy vào DLQ: đây chỉ là tín hiệu làm mới, client bù bằng cách tải
     * lại dữ liệu khi kết nối lại. Một thread (subscription không shared: thêm consumer trong cùng instance là nhận trùng).
     * <p>
     * <b>Mất kết nối broker</b>: subscription non-durable biến mất cùng kết nối; message gửi trước khi container tạo lại
     * consumer (vòng recovery của DMLC) bị broker bỏ với instance này, trong khi socket của trình duyệt vẫn mở → client
     * không biết để resync. Container ({@link RealtimeListenerContainer}) phát {@link RealtimeSubscriptionRestoredEvent}
     * mỗi lần consumer được tạo lại → {@code WebSocketSessionTracker} đóng socket cũ, client nối lại và tải lại số liệu.
     * Không bật {@code reconnectAttempts} của client Artemis: client tự nối lại thì DMLC không thấy lỗi, không phát sự kiện.
     */
    @Bean(name = TOPIC_BROADCAST_FACTORY)
    public DefaultJmsListenerContainerFactory topicBroadcastListenerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            JmsListenerErrorHandler errorHandler, ApplicationEventPublisher eventPublisher) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory() {
            @Override
            protected DefaultMessageListenerContainer createContainerInstance() {
                return new RealtimeListenerContainer(eventPublisher);
            }
        };
        configure(factory, configurer, connectionFactory, errorHandler);
        factory.setPubSubDomain(true);
        factory.setSubscriptionDurable(false);
        factory.setSubscriptionShared(false);
        factory.setSessionTransacted(false);
        factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
        factory.setConcurrency("1");
        return factory;
    }

    @Bean
    @Primary
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        return template(connectionFactory, messageConverter, false);
    }

    @Bean(name = TOPIC_JMS_TEMPLATE)
    public JmsTemplate topicJmsTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        return template(connectionFactory, messageConverter, true);
    }

    private static DefaultJmsListenerContainerFactory listenerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            JmsListenerErrorHandler errorHandler) {
        return configure(new DefaultJmsListenerContainerFactory(), configurer, connectionFactory, errorHandler);
    }

    private static DefaultJmsListenerContainerFactory configure(DefaultJmsListenerContainerFactory factory,
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            JmsListenerErrorHandler errorHandler) {
        configurer.configure(factory, ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        factory.setErrorHandler(errorHandler);
        return factory;
    }

    /**
     * Container của listener realtime: lần tạo consumer đầu tiên là lúc khởi động; mỗi lần sau là subscription được tạo
     * lại sau lỗi (consumer được cache suốt vòng đời invoker, chỉ tạo lại khi recovery) → phát
     * {@link RealtimeSubscriptionRestoredEvent} SAU khi consumer đã đăng ký (message từ lúc này không còn bị bỏ).
     */
    static final class RealtimeListenerContainer extends DefaultMessageListenerContainer {

        private final ApplicationEventPublisher eventPublisher;
        private final AtomicBoolean subscribedOnce = new AtomicBoolean();

        RealtimeListenerContainer(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
        }

        @Override
        protected MessageConsumer createListenerConsumer(Session session) throws JMSException {
            MessageConsumer consumer = super.createListenerConsumer(session);
            if (subscribedOnce.getAndSet(true)) {
                eventPublisher.publishEvent(new RealtimeSubscriptionRestoredEvent(getDestinationDescription()));
            }
            return consumer;
        }
    }

    private static JmsTemplate template(ConnectionFactory connectionFactory, MessageConverter messageConverter,
                                        boolean pubSubDomain) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setPubSubDomain(pubSubDomain);
        return template;
    }
}
