package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;

import java.util.Arrays;
import java.util.Optional;

/**
 * Loại message gửi qua Transactional Outbox: alias (lưu ở cột {@code outbox_messages.type}, trùng
 * {@link MessageTypes#TYPE_ID_MAPPINGS}) → destination JMS. Không đổi alias của loại đã dùng: bản ghi PENDING cũ sẽ
 * không gửi được.
 */
public enum OutboxType {

    ORDER_PLACED(MessageTypes.ORDER_PLACED, OrderPlacedMessage.class, Destinations.ORDER_PLACED, true),
    ORDER_STATUS_CHANGED(MessageTypes.ORDER_STATUS_CHANGED, OrderStatusChangedMessage.class,
            Destinations.ORDER_STATUS_CHANGED, true);

    private final String alias;
    private final Class<?> payloadType;
    private final String destination;
    private final boolean topic;

    OutboxType(String alias, Class<?> payloadType, String destination, boolean topic) {
        if (MessageTypes.TYPE_ID_MAPPINGS.get(alias) != payloadType) {
            throw new IllegalStateException("Outbox alias " + alias + " must map to " + payloadType.getName()
                    + " in MessageTypes.TYPE_ID_MAPPINGS");
        }
        this.alias = alias;
        this.payloadType = payloadType;
        this.destination = destination;
        this.topic = topic;
    }

    public String alias() {
        return alias;
    }

    public Class<?> payloadType() {
        return payloadType;
    }

    public String destination() {
        return destination;
    }

    /** {@code true}: topic (publish/subscribe), {@code false}: queue. */
    public boolean topic() {
        return topic;
    }

    public static Optional<OutboxType> fromAlias(String alias) {
        return Arrays.stream(values()).filter(type -> type.alias.equals(alias)).findFirst();
    }
}
