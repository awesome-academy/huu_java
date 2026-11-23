package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Thông báo Slack dạng Block Kit: cấu trúc payload, escape mrkdwn, giới hạn số dòng. */
class SlackNotificationServiceTest {

    private static final String WEBHOOK = "https://hooks.slack.test/services/T000/B000/XXX";

    private MockRestServiceServer server;
    private SlackNotificationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new SlackNotificationService(builder.build());
        ReflectionTestUtils.setField(service, "webhookUrl", WEBHOOK);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void orderPlaced_postsBlockKitPayload() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("Đơn hàng mới #42 — Nguyễn Văn A — 120.000 ₫"))
                .andExpect(jsonPath("$.blocks[0].type").value("header"))
                .andExpect(jsonPath("$.blocks[0].text.text").value("🛒 Đơn hàng mới #42"))
                .andExpect(jsonPath("$.blocks[1].fields[0].text").value("*Khách hàng:*\nNguyễn Văn A"))
                .andRespond(withSuccess());

        service.sendOrderPlaced(order("Nguyễn Văn A", "Ít đá", items(2)));

        server.verify();
    }

    @Test
    void isEnabled_requiresFlagAndWebhook() {
        assertThat(service.isEnabled()).isTrue();

        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.isEnabled()).isFalse();

        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "webhookUrl", " ");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void webhookError_isThrown_soBrokerRedelivers_withoutLeakingWebhookUrl() {
        server.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        assertThatThrownBy(() -> service.sendOrderPlaced(order("A", null, items(1))))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("XXX")
                .hasNoCause();
    }

    @Test
    void webhookIoError_isThrown_withoutLeakingWebhookUrl() {
        server.expect(requestTo(WEBHOOK)).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });

        assertThatThrownBy(() -> service.sendOrderPlaced(order("A", null, items(1))))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageNotContaining("hooks.slack.test")
                .hasNoCause();
    }

    @Test
    void malformedWebhookUrl_isThrown_withoutLeakingWebhookUrl() {
        // Dán sai cấu hình: lỗi dựng URI (không phải RestClientException) thường nối nguyên URL vào message
        ReflectionTestUtils.setField(service, "webhookUrl", "https://hooks.slack.test:SECRET-TOKEN/services");

        assertThatThrownBy(() -> service.sendOrderPlaced(order("A", null, items(1))))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageNotContaining("SECRET-TOKEN")
                .hasMessageNotContaining("hooks.slack.test")
                .hasNoCause();
    }

    @Test
    void userInput_isEscaped_soItCannotPingChannelsOrInjectLinks() {
        Map<String, Object> payload = SlackMessageBuilder.orderPlaced(
                order("<!channel> & co", "<https://evil.example|bấm vào đây>", items(1)));

        String json = payload.toString();
        assertThat(json)
                .contains("&lt;!channel&gt; &amp; co", "&lt;https://evil.example|bấm vào đây&gt;")
                .doesNotContain("<!channel>", "<https://evil.example");
    }

    @Test
    void longOrders_areTruncated() {
        Map<String, Object> payload = SlackMessageBuilder.orderPlaced(order("A", null, items(20)));

        String json = payload.toString();
        assertThat(json).contains("Món 15 × 1", "_… và 5 món khác_").doesNotContain("Món 16 ×");
    }

    @Test
    void truncate_neverSplitsSurrogatePair() {
        // "ab😀c": '😀' chiếm index 2-3; cắt ở 4 (3 ký tự + "…") sẽ rơi giữa cặp surrogate
        String result = SlackMessageBuilder.truncate("ab😀cdef", 4);

        assertThat(result).isEqualTo("ab…");
        assertThat(result.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();
        assertThat(SlackMessageBuilder.truncate("ab😀cdef", 5)).isEqualTo("ab😀…");
    }

    @Test
    void truncate_neverSplitsEscapedEntity() {
        String escaped = SlackMessageBuilder.escape("abc<def");   // "abc&lt;def"

        assertThat(SlackMessageBuilder.truncate(escaped, 6)).isEqualTo("abc…");
        assertThat(SlackMessageBuilder.truncate(escaped, 8)).isEqualTo("abc&lt;…");
        assertThat(SlackMessageBuilder.truncate(SlackMessageBuilder.escape("a&b"), 5)).isEqualTo("a…");
    }

    @Test
    void longEmojiItemList_staysValidJson() {
        // 15 dòng × tên ~250 ký tự có emoji → vượt 3000 ký tự, bị cắt
        List<OrderPlacedMessage.OrderLineItem> items = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            items.add(new OrderPlacedMessage.OrderLineItem("🍜<".repeat(80) + i, 1, BigDecimal.ONE, BigDecimal.ONE));
        }
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.blocks[4].text.text").exists())
                .andRespond(withSuccess());

        service.sendOrderPlaced(order("A", null, items));

        server.verify();
    }

    @Test
    void optionalFields_areOmitted_whenMissing() {
        OrderPlacedMessage event = new OrderPlacedMessage(7L, "B", "b@example.com", " ", "HN", null,
                BigDecimal.TEN, null, items(1));

        String json = SlackMessageBuilder.orderPlaced(event).toString();

        assertThat(json).doesNotContain("Số điện thoại", "Ghi chú", "Thời gian");
    }

    // -------------------------------------------------------

    private static OrderPlacedMessage order(String name, String note, List<OrderPlacedMessage.OrderLineItem> items) {
        return new OrderPlacedMessage(42L, name, "a@example.com", "0901234567", "12 Lê Lợi, Q1", note,
                new BigDecimal("120000"), LocalDateTime.of(2026, 11, 20, 9, 30), items);
    }

    private static List<OrderPlacedMessage.OrderLineItem> items(int count) {
        List<OrderPlacedMessage.OrderLineItem> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(new OrderPlacedMessage.OrderLineItem("Món " + i, 1, new BigDecimal("6000"), new BigDecimal("6000")));
        }
        return items;
    }
}
