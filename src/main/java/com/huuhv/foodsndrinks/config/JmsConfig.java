package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.config.properties.OutboxProperties;
import com.huuhv.foodsndrinks.service.messaging.MessageTypes;
import jakarta.jms.ConnectionFactory;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

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
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        factory.setErrorHandler(errorHandler);
        return factory;
    }

    private static JmsTemplate template(ConnectionFactory connectionFactory, MessageConverter messageConverter,
                                        boolean pubSubDomain) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setPubSubDomain(pubSubDomain);
        return template;
    }
}
