package com.huuhv.foodsndrinks.service.messaging;

/**
 * Tên destination JMS (address của Artemis) — nguồn duy nhất, không viết chuỗi rải rác trong {@code @JmsListener}/
 * producer. Retry + dead-letter của từng address khai báo ở {@code src/main/resources/artemis/broker.properties} (dùng
 * chung cho broker embedded và broker riêng); đổi tên ở đây thì đổi cả file đó ({@code ArtemisEmbeddedConfigTest} kiểm tra).
 */
public final class Destinations {

    /** Topic (multicast): một message mỗi đơn hàng vừa đặt, mỗi kênh thông báo là một shared durable subscription. */
    public static final String ORDER_PLACED = "order.placed";

    /** Queue (anycast) nhận message của {@link #ORDER_PLACED} đã hết số lần giao lại. */
    public static final String ORDER_PLACED_DLQ = "DLQ.order-placed";

    private Destinations() {
    }
}
