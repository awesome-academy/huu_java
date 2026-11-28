package com.huuhv.foodsndrinks.service.soap;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Tỷ giá GIẢ cho endpoint mock ({@code controller/soap/ExchangeRateMockEndpoint}) — để môi trường local/demo có một
 * "dịch vụ tỷ giá bên ngoài" mà không cần Internet. Chỉ tồn tại khi {@code app.soap.exchange-rate.mock-endpoint.enabled}
 * bật (dev, compose); prod luôn tắt.
 * <p>
 * Giá trị dao động ±0,5% quanh một mức cố định, đổi mỗi giờ nhưng XÁC ĐỊNH theo (cặp tiền, giờ): cùng một giờ, mọi
 * instance và mọi lần gọi trả cùng một số — không cần trạng thái dùng chung, test không phụ thuộc may rủi.
 */
@Service
@ConditionalOnBooleanProperty(SoapProperties.ExchangeRate.MOCK_ENDPOINT_ENABLED)
public class MockExchangeRateService {

    /** Giá trị của 1 đơn vị mỗi loại tiền, tính bằng VND (mức tham khảo, không phải dữ liệu thật). */
    private static final Map<String, BigDecimal> VND_PER_UNIT = Map.of(
            "VND", BigDecimal.ONE,
            "USD", new BigDecimal("25400"),
            "EUR", new BigDecimal("27600"),
            "JPY", new BigDecimal("168"));

    private static final int RATE_SCALE = 6;

    /** Tỷ giá giả tại thời điểm {@code now}. */
    public record Quote(BigDecimal rate, Instant asOf) {
    }

    /**
     * @return 1 {@code base} = bao nhiêu {@code quote}, có hiệu lực từ đầu giờ hiện tại (UTC)
     * @throws IllegalArgumentException cặp tiền không hỗ trợ, hoặc hai mã trùng nhau
     */
    public Quote quote(String base, String quote, Instant now) {
        BigDecimal baseInVnd = VND_PER_UNIT.get(base);
        BigDecimal quoteInVnd = VND_PER_UNIT.get(quote);
        if (baseInVnd == null || quoteInVnd == null || base.equals(quote)) {
            throw new IllegalArgumentException("Unsupported currency pair");
        }
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        // Hệ số 0,995 .. 1,005, seed theo (cặp tiền, giờ)
        long seed = 31L * (base + "/" + quote).hashCode() + hour.getEpochSecond();
        double factor = 0.995 + new SplittableRandom(seed).nextDouble() * 0.01;
        BigDecimal rate = baseInVnd.divide(quoteInVnd, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(factor))
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        return new Quote(rate, hour);
    }
}
