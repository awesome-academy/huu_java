package com.huuhv.foodsndrinks.service.soap;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Tỷ giá giả của endpoint mock: xác định theo (cặp tiền, giờ), dao động ±0,5% quanh mức tham khảo. */
class MockExchangeRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-28T03:17:42Z");

    private final MockExchangeRateService service = new MockExchangeRateService();

    @Test
    void usdToVnd_staysWithinHalfAPercentOfTheReferenceRate_withSixDecimals() {
        MockExchangeRateService.Quote quote = service.quote("USD", "VND", NOW);

        assertThat(quote.rate()).isBetween(new BigDecimal("25273"), new BigDecimal("25527"));
        assertThat(quote.rate().scale()).isEqualTo(6);
        // Có hiệu lực từ đầu giờ (UTC)
        assertThat(quote.asOf()).isEqualTo(Instant.parse("2026-11-28T03:00:00Z"));
    }

    @Test
    void sameHour_sameRate_soEveryInstanceAndEveryCallAgree() {
        MockExchangeRateService another = new MockExchangeRateService();

        assertThat(service.quote("USD", "VND", NOW))
                .isEqualTo(service.quote("USD", "VND", NOW.plus(Duration.ofMinutes(40))))
                .isEqualTo(another.quote("USD", "VND", NOW));
    }

    @Test
    void rateChangesFromHourToHour_andDiffersPerPair() {
        assertThat(service.quote("USD", "VND", NOW).rate())
                .isNotEqualByComparingTo(service.quote("USD", "VND", NOW.plus(Duration.ofHours(1))).rate());
        // Chiều ngược lại là nghịch đảo (xấp xỉ), luôn dương
        assertThat(service.quote("VND", "USD", NOW).rate()).isBetween(new BigDecimal("0.000039"), new BigDecimal("0.000040"));
        assertThat(service.quote("EUR", "JPY", NOW).rate()).isPositive();
    }

    @Test
    void unsupportedOrIdenticalCurrencies_areRejected_withoutEchoingTheInput() {
        for (String[] pair : new String[][]{{"USD", "XXX"}, {"ABC", "VND"}, {"USD", "USD"}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.quote(pair[0], pair[1], NOW))
                    .withMessage("Unsupported currency pair");
        }
    }
}
