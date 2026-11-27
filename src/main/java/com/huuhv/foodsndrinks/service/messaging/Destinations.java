package com.huuhv.foodsndrinks.service.messaging;

/**
 * Tên destination JMS (address của Artemis) — nguồn duy nhất, không viết chuỗi rải rác trong {@code @JmsListener}/
 * producer. Retry + dead-letter của từng address khai báo ở {@code src/main/resources/artemis/broker.properties} (dùng
 * chung cho broker embedded và broker riêng); đổi tên ở đây thì đổi cả file đó ({@code ArtemisEmbeddedConfigTest} kiểm tra).
 */
public final class Destinations {

    /** Topic (multicast): một message mỗi đơn hàng vừa đặt, mỗi kênh thông báo là một shared durable subscription. */
    public static final String ORDER_PLACED = "order.placed";

    /**
     * Topic (multicast): một message mỗi lần admin đổi trạng thái đơn. Chỉ có subscription non-durable của từng instance
     * (đẩy WebSocket tới chủ đơn + admin) — không có hàng đợi tạo sẵn, không retry/DLQ: là tín hiệu làm mới, trang tự tải
     * lại trạng thái khi kết nối lại.
     */
    public static final String ORDER_STATUS_CHANGED = "order.status-changed";

    /** Queue (anycast) nhận message của {@link #ORDER_PLACED} đã hết số lần giao lại. */
    public static final String ORDER_PLACED_DLQ = "DLQ.order-placed";

    /**
     * Shared durable subscription của {@link #ORDER_PLACED} — mỗi kênh thông báo một subscription (một hàng đợi riêng trên
     * broker, tên = tên subscription, tạo sẵn trong {@code artemis/broker.properties} để message tới trước khi listener
     * đăng ký không bị bỏ). Không đổi tên: subscription cũ cùng message trong đó bị bỏ lại trên broker.
     */
    public static final String ORDER_PLACED_EMAIL_SUBSCRIPTION = "order-placed-email";
    public static final String ORDER_PLACED_SLACK_SUBSCRIPTION = "order-placed-slack";

    /**
     * Queue (anycast, point-to-point): một message mỗi lần import Excel cần xử lý — đúng MỘT consumer (của một instance
     * bất kỳ) nhận mỗi message. Queue cùng tên được tạo sẵn trong {@code artemis/broker.properties}.
     */
    public static final String EXCEL_IMPORT = "excel.import";

    /** Queue (anycast) nhận message của {@link #EXCEL_IMPORT} đã hết số lần giao lại. */
    public static final String EXCEL_IMPORT_DLQ = "DLQ.excel-import";

    private Destinations() {
    }
}
