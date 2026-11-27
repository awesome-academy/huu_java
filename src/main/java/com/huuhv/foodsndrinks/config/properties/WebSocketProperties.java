package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * WebSocket/STOMP ({@code app.websocket.*}).
 *
 * @param allowedOrigins             origin được mở WebSocket tới {@code /ws-stomp} ({@code WEBSOCKET_ALLOWED_ORIGINS}).
 *                                   Rỗng = chỉ same-origin (Spring so header {@code Origin} với scheme/host/port của
 *                                   request). Chạy sau load balancer thì {@code Host} có thể bị đổi → profile prod bắt
 *                                   buộc khai báo origin public ({@code config/WebSocketOriginCheck}). Cùng quy tắc với
 *                                   CORS ({@link OriginValidator}).
 * @param revalidateInterval         chu kỳ job kiểm tra lại các kết nối đang mở (tài khoản bị khoá/đổi role, session HTTP
 *                                   đã kết thúc) — thời gian tối đa một socket bị thu hồi còn nhận được dữ liệu
 *                                   ({@code WEBSOCKET_REVALIDATE_INTERVAL}).
 * @param heartbeat                  heartbeat STOMP hai chiều; load balancer phải để idle timeout &gt; 2 lần giá trị này.
 * @param maxSubscriptionsPerSession số SUBSCRIBE tối đa đang mở trên một kết nối (trang thật dùng 1) — chặn client làm
 *                                   phình registry của simple broker và nhân số message mỗi sự kiện
 *                                   ({@code security/StompSubscriptionLimitInterceptor}).
 * @param maxConnectionsPerUser      số kết nối WebSocket tối đa của một user trên MỖI instance (≈ số tab mở trang có
 *                                   realtime); kết nối vượt bị đóng ngay ({@code WebSocketSessionTracker}).
 */
@Validated
@ConfigurationProperties("app.websocket")
public record WebSocketProperties(
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("60s") @DurationMin(seconds = 1) Duration revalidateInterval,
        @DefaultValue("10s") @DurationMin(seconds = 1) @DurationMax(minutes = 5) Duration heartbeat,
        @DefaultValue("5") @Min(1) @Max(50) int maxSubscriptionsPerSession,
        @DefaultValue("10") @Min(1) @Max(100) int maxConnectionsPerUser) {

    public WebSocketProperties {
        allowedOrigins = OriginValidator.normalize(allowedOrigins,
                "app.websocket.allowed-origins (WEBSOCKET_ALLOWED_ORIGINS)");
    }
}
