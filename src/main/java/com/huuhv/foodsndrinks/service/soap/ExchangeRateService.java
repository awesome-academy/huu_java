package com.huuhv.foodsndrinks.service.soap;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.dto.response.ConvertedAmountResDto;
import com.huuhv.foodsndrinks.exception.ExchangeRateUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tỷ giá USD/VND cho dashboard admin, lấy từ dịch vụ SOAP bên ngoài ({@link ExchangeRateSoapClient}) và giữ trong bộ nhớ
 * của instance.
 * <p>
 * <b>Request của người dùng không bao giờ gọi SOAP</b>: {@link #convertFromVnd} chỉ đọc giá trị đã có. Việc gọi dịch vụ do
 * {@link ExchangeRateRefreshJob} làm nền ({@link #refreshIfDue()}) — nếu trang dashboard tự gọi khi cache hết hạn thì mỗi
 * {@code cache-ttl} một admin phải chờ tới hết connect + read timeout trên thread Tomcat khi dịch vụ chậm, và (với
 * endpoint mock của chính app) còn cần thêm một thread Tomcat rảnh để phục vụ lời gọi đó.
 * <p>
 * Dịch vụ lỗi/quá thời gian → WARN (chỉ mã lý do), giữ giá trị cũ và không gọi lại trong {@code retry-after-failure}.
 * Giá trị cũ hơn {@value #STALE_AFTER_TTL_MULTIPLIER} × {@code cache-ttl} bị coi là không còn → dashboard ghi "Không lấy
 * được tỷ giá". Không có method nào ném exception ra controller: SOAP hỏng không làm hỏng trang.
 * <p>
 * Cache theo từng instance (không ShedLock, không DB): tỷ giá không phải dữ liệu của app, hai instance lệch nhau vài phút
 * không sao, và thời điểm lấy luôn được hiển thị kèm.
 */
@Slf4j
@Service
public class ExchangeRateService {

    /** Dashboard quy đổi doanh thu (VND) sang đồng này. */
    public static final String FOREIGN_CURRENCY = "USD";
    static final String LOCAL_CURRENCY = "VND";
    static final int STALE_AFTER_TTL_MULTIPLIER = 2;

    private final ExchangeRateSoapClient client;
    private final SoapProperties.ExchangeRate properties;
    private final InstantSource clock;
    /** Chỉ một lần làm mới chạy tại một thời điểm; lần khác bỏ qua (không chờ). */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot snapshot;
    private volatile Instant lastFailureAt;

    @Autowired
    public ExchangeRateService(ExchangeRateSoapClient client, SoapProperties soapProperties) {
        this(client, soapProperties.exchangeRate(), InstantSource.system());
    }

    ExchangeRateService(ExchangeRateSoapClient client, SoapProperties.ExchangeRate properties, InstantSource clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param rate 1 USD = {@code rate} VND */
    private record Snapshot(BigDecimal rate, Instant fetchedAt) {
    }

    /** Có cấu hình URL dịch vụ tỷ giá ({@code SOAP_EXCHANGE_RATE_URL}) hay không. Tắt thì dashboard không nhắc tới tỷ giá. */
    public boolean isEnabled() {
        return properties.hasUrl();
    }

    /**
     * Quy đổi một số tiền VND sang USD bằng tỷ giá đang giữ. Không gọi mạng.
     *
     * @return rỗng nếu tính năng tắt, chưa lấy được tỷ giá lần nào, hoặc tỷ giá đang giữ đã quá cũ
     */
    public Optional<ConvertedAmountResDto> convertFromVnd(BigDecimal amountVnd) {
        Snapshot current = usableSnapshot();
        if (current == null || amountVnd == null) {
            return Optional.empty();
        }
        return Optional.of(new ConvertedAmountResDto(
                amountVnd.divide(current.rate(), 2, RoundingMode.HALF_UP),
                FOREIGN_CURRENCY,
                current.rate(),
                LocalDateTime.ofInstant(current.fetchedAt(), ZoneId.systemDefault())));
    }

    /**
     * Gọi dịch vụ tỷ giá nếu đã tới lúc: chưa có giá trị hoặc giá trị cũ hơn {@code cache-ttl}, và không đang trong
     * {@code retry-after-failure} của lần lỗi trước. Gọi bởi job nền; không bao giờ ném exception.
     */
    public void refreshIfDue() {
        if (!isEnabled() || !refreshLock.tryLock()) {
            return;
        }
        try {
            Instant now = clock.instant();
            if (!isDue(now)) {
                return;
            }
            try {
                ExchangeRateSoapClient.Quote quote = client.getRate(FOREIGN_CURRENCY, LOCAL_CURRENCY);
                snapshot = new Snapshot(quote.rate(), now);
                lastFailureAt = null;
                log.debug("Exchange rate refreshed: 1 {} = {} {}", FOREIGN_CURRENCY, quote.rate(), LOCAL_CURRENCY);
            } catch (ExchangeRateUnavailableException ex) {
                lastFailureAt = now;
                // Chỉ mã lý do của app (không có dữ liệu của máy chủ bên kia) — xem ExchangeRateUnavailableException
                log.warn("Không lấy được tỷ giá {}/{} từ dịch vụ SOAP ({}), thử lại sau {} giây",
                        FOREIGN_CURRENCY, LOCAL_CURRENCY, ex.getMessage(), properties.retryAfterFailure().toSeconds());
            } catch (RuntimeException ex) {
                lastFailureAt = now;
                log.error("Unexpected error while refreshing the exchange rate", ex);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isDue(Instant now) {
        Instant failedAt = lastFailureAt;
        if (failedAt != null && now.isBefore(failedAt.plus(properties.retryAfterFailure()))) {
            return false;
        }
        Snapshot current = snapshot;
        return current == null || !now.isBefore(current.fetchedAt().plus(properties.cacheTtl()));
    }

    private Snapshot usableSnapshot() {
        Snapshot current = snapshot;
        if (!isEnabled() || current == null) {
            return null;
        }
        Duration maxAge = properties.cacheTtl().multipliedBy(STALE_AFTER_TTL_MULTIPLIER);
        return clock.instant().isAfter(current.fetchedAt().plus(maxAge)) ? null : current;
    }
}
