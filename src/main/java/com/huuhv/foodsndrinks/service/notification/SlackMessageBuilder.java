package com.huuhv.foodsndrinks.service.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payload Slack (Incoming Webhook) dạng Block Kit cho thông báo đơn hàng mới — Slack không hiển thị HTML, Block Kit là
 * định dạng có cấu trúc tương đương (tiêu đề, các ô thông tin, danh sách món). {@code text} là bản ngắn cho thông báo
 * đẩy/client không hiển thị block.
 * <p>
 * Dữ liệu người dùng nhập (tên, địa chỉ, ghi chú, tên món) đi vào {@code mrkdwn} nên phải {@link #escape escape}
 * {@code & < >}: nếu không, khách có thể chèn {@code <!channel>} (ping cả kênh) hay link {@code <https://...|text>}.
 * Độ dài bị cắt theo giới hạn của Slack (section 3000 ký tự, header 150) để webhook không trả lỗi 400.
 */
final class SlackMessageBuilder {

    static final int MAX_ITEM_LINES = 15;
    private static final int MAX_SECTION_TEXT = 3000;
    private static final int MAX_FIELD_TEXT = 2000;

    private SlackMessageBuilder() {
    }

    static Map<String, Object> orderPlaced(OrderPlacedEvent event) {
        String total = OrderMessageFormatter.formatMoney(event.totalPrice()) + " ₫";

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "header",
                "text", Map.of("type", "plain_text", "text", "🛒 Đơn hàng mới #" + event.orderId(), "emoji", true)));

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(mrkdwn("*Khách hàng:*\n" + escape(event.customerName()), MAX_FIELD_TEXT));
        fields.add(mrkdwn("*Email:*\n" + escape(event.customerEmail()), MAX_FIELD_TEXT));
        if (hasText(event.customerPhone())) {
            fields.add(mrkdwn("*Số điện thoại:*\n" + escape(event.customerPhone()), MAX_FIELD_TEXT));
        }
        if (event.orderedAt() != null) {
            fields.add(mrkdwn("*Thời gian:*\n" + OrderMessageFormatter.formatDateTime(event.orderedAt()), MAX_FIELD_TEXT));
        }
        fields.add(mrkdwn("*Tổng tiền:*\n" + total, MAX_FIELD_TEXT));
        blocks.add(Map.of("type", "section", "fields", fields));

        StringBuilder shipping = new StringBuilder("*Địa chỉ giao hàng:*\n").append(escape(event.shippingAddress()));
        if (hasText(event.note())) {
            shipping.append("\n*Ghi chú:* _").append(escape(event.note())).append('_');
        }
        blocks.add(section(shipping.toString()));

        blocks.add(Map.of("type", "divider"));
        blocks.add(section(itemLines(event)));
        blocks.add(Map.of("type", "context", "elements", List.of(
                mrkdwn("Vào Trang quản trị → Đơn hàng → #" + event.orderId() + " để xác nhận và xử lý đơn.", MAX_FIELD_TEXT))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "Đơn hàng mới #" + event.orderId() + " — " + escape(event.customerName()) + " — " + total);
        payload.put("blocks", blocks);
        return payload;
    }

    /** Escape ký tự điều khiển của Slack mrkdwn (theo tài liệu Slack: chỉ cần {@code & < >}). */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String itemLines(OrderPlacedEvent event) {
        StringBuilder sb = new StringBuilder("*Chi tiết đơn hàng:*");
        List<OrderPlacedEvent.OrderLineItem> items = event.items();
        int shown = Math.min(items.size(), MAX_ITEM_LINES);
        for (int i = 0; i < shown; i++) {
            OrderPlacedEvent.OrderLineItem item = items.get(i);
            sb.append("\n• ").append(escape(item.productName()))
                    .append(" × ").append(item.quantity())
                    .append(" — ").append(OrderMessageFormatter.formatMoney(item.subtotal())).append(" ₫");
        }
        if (items.size() > shown) {
            sb.append("\n_… và ").append(items.size() - shown).append(" món khác_");
        }
        return sb.toString();
    }

    private static Map<String, Object> section(String text) {
        return Map.of("type", "section", "text", mrkdwn(text, MAX_SECTION_TEXT));
    }

    private static Map<String, Object> mrkdwn(String text, int maxLength) {
        return Map.of("type", "mrkdwn", "text", truncate(text, maxLength));
    }

    /**
     * Cắt còn tối đa {@code maxLength} ký tự UTF-16 (giới hạn của Slack), kể cả dấu "…". Không cắt giữa cặp surrogate
     * (emoji) — nửa surrogate lẻ làm Jackson lỗi và mất cả thông báo — và không cắt giữa entity {@code &amp;}/{@code &lt;}/
     * {@code &gt;} do {@link #escape} sinh ra (mọi {@code &} trong text đã escape đều là đầu một entity).
     */
    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = maxLength - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        int amp = text.lastIndexOf('&', end - 1);
        if (amp >= 0 && text.indexOf(';', amp) >= end) {
            end = amp;
        }
        return text.substring(0, end) + "…";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
