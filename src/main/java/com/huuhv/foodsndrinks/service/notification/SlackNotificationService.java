package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.exception.NotificationDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thông báo Slack (Incoming Webhook). Gọi đồng bộ từ consumer JMS ({@code OrderPlacedSlackConsumer}): lỗi được ném ra để
 * broker giao lại message, không nuốt lỗi ở đây. {@code RestClient} có timeout ({@code AppConfig}) nên webhook treo không
 * giữ thread listener mãi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackNotificationService {

    private final RestClient restClient;

    @Value("${app.notification.slack.webhook-url:}")
    private String webhookUrl;

    @Value("${app.notification.slack.enabled:true}")
    private boolean enabled;

    /** Tắt ({@code SLACK_NOTIFY_ENABLED=false}) hoặc chưa cấu hình webhook → consumer bỏ qua message, không thử lại. */
    public boolean isEnabled() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    /**
     * @throws NotificationDeliveryException webhook lỗi/timeout. Thông điệp không chứa URL webhook (là secret) — exception
     *                                       của {@code RestClient} nối URL vào message nên không giữ làm cause.
     */
    public void sendOrderPlaced(OrderPlacedMessage message) {
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .body(SlackMessageBuilder.orderPlaced(message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException("Slack webhook returned HTTP " + ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new NotificationDeliveryException("Slack webhook call failed (" + ex.getClass().getSimpleName() + ")");
        }
    }
}
