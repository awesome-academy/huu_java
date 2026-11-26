package com.huuhv.foodsndrinks.service.websocket;

/**
 * Đường dẫn WebSocket/STOMP — nguồn duy nhất cho {@code config/WebSocketConfig}, {@code config/WebSocketSecurityConfig}
 * và service gửi message. Đặt ở {@code service} để service dùng được mà không import {@code config}. Template truyền
 * đích cho JS qua thuộc tính {@code data-*} (Thymeleaf 3.1 không cho gọi {@code T(...)} trong template) — đổi ở đây thì
 * đổi cả template ({@code WebSocketLayoutTest} kiểm tra).
 */
public final class StompDestinations {

    /** Endpoint handshake (WebSocket thuần, không SockJS). Đi qua filter chain web: xác thực bằng session. */
    public static final String ENDPOINT = "/ws-stomp";
    /** Mọi URL của endpoint (dùng cho matcher của {@code SecurityConfig}). */
    public static final String ENDPOINT_PATTERN = ENDPOINT + "/**";

    /** Prefix của {@code @MessageMapping} — hiện chưa có handler nào nên mọi SEND tới đây đều bị chặn. */
    public static final String APP_PREFIX = "/app";
    /** Broadcast tới mọi subscriber. */
    public static final String TOPIC_PREFIX = "/topic";
    /** Hàng đợi riêng từng user — client không SUBSCRIBE thẳng, chỉ qua {@link #USER_PREFIX}. */
    public static final String QUEUE_PREFIX = "/queue";
    /** Đích theo user: client SUBSCRIBE {@code /user/queue/...}, server gửi bằng {@code convertAndSendToUser}. */
    public static final String USER_PREFIX = "/user";

    /** Sự kiện đơn hàng cho admin (đơn mới, số đơn chờ xử lý). Chỉ {@code ROLE_ADMIN} được SUBSCRIBE. */
    public static final String ADMIN_ORDERS = TOPIC_PREFIX + "/admin/orders";

    private StompDestinations() {
    }
}
