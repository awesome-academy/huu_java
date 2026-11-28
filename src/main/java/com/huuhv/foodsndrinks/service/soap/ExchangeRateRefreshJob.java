package com.huuhv.foodsndrinks.service.soap;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Làm mới tỷ giá ở nền để request dashboard không bao giờ phải chờ dịch vụ SOAP ({@link ExchangeRateService}). Mỗi lượt
 * chỉ hỏi "đã tới lúc chưa" — lời gọi SOAP thật chỉ xảy ra mỗi {@code cache-ttl} (hoặc sau {@code retry-after-failure} khi
 * lần trước lỗi). Lượt đầu chờ {@code refresh-initial-delay} để Tomcat kịp nhận request (endpoint mock nằm trong chính app).
 * <p>
 * <b>Không {@code @SchedulerLock}</b> — ngoại lệ có chủ ý như {@code OutboxRelayJob}/{@code WebSocketSessionRevalidationJob}:
 * cache tỷ giá nằm trong bộ nhớ từng instance, khoá chung sẽ làm các instance khác không bao giờ có tỷ giá. Job không đụng
 * DB nên không tính vào ngân sách connection.
 */
@Component
@RequiredArgsConstructor
public class ExchangeRateRefreshJob {

    private final ExchangeRateService exchangeRateService;

    @Scheduled(initialDelayString = "${app.soap.exchange-rate.refresh-initial-delay:15s}",
            fixedDelayString = "${app.soap.exchange-rate.refresh-interval:30s}")
    public void refresh() {
        // refreshIfDue không ném exception (lỗi đã được ghi log và tính backoff ở đó)
        exchangeRateService.refreshIfDue();
    }
}
