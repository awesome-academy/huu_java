package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.config.properties.JmsBrokerProperties;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gửi {@link OrderPlacedMessage} lên topic {@code order.placed} SAU KHI transaction checkout commit — consumer không bao
 * giờ nhận đơn chưa commit, checkout rollback thì không có message. Mỗi kênh thông báo là một subscription của topic
 * ({@link OrderPlacedEmailConsumer}, {@link OrderPlacedSlackConsumer}).
 * <p>
 * Gửi lỗi (broker chết — lỗi sau tối đa {@code connectTimeoutMillis}/{@code callTimeout} của {@code broker-url}) chỉ ghi
 * log ERROR, không ném ra: đơn đã commit, không trả lỗi cho khách. Thông báo của đơn đó bị mất — Transactional Outbox
 * (issue 5.3) xử lý.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedProducer {

    @Qualifier(JmsBrokerProperties.TOPIC_JMS_TEMPLATE)
    private final JmsTemplate topicJmsTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedMessage message) {
        try {
            topicJmsTemplate.convertAndSend(Destinations.ORDER_PLACED, message);
            log.debug("Published {} for order #{}", Destinations.ORDER_PLACED, message.orderId());
        } catch (RuntimeException ex) {
            log.error("Failed to publish {} for order #{}, its notifications are lost", Destinations.ORDER_PLACED,
                    message.orderId(), ex);
        }
    }
}
