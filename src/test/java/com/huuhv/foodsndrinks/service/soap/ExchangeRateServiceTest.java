package com.huuhv.foodsndrinks.service.soap;

import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.dto.response.ConvertedAmountResDto;
import com.huuhv.foodsndrinks.exception.ExchangeRateUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExchangeRateService}: tỷ giá giữ trong bộ nhớ, chỉ job nền gọi SOAP ({@code refreshIfDue}), request chỉ đọc; dịch
 * vụ lỗi thì giữ giá trị cũ có thời hạn, có backoff, và không bao giờ ném lỗi ra ngoài. Client là mock, đồng hồ điều khiển
 * được.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExchangeRateServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-28T03:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration RETRY = Duration.ofMinutes(1);

    private final ExchangeRateSoapClient client = mock(ExchangeRateSoapClient.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final InstantSource clock = now::get;
    private final ExchangeRateService service = new ExchangeRateService(client, properties("http://rates.example/ws"), clock);

    private static SoapProperties.ExchangeRate properties(String url) {
        return new SoapProperties.ExchangeRate(url, "svc", "secret", Duration.ofSeconds(3), Duration.ofSeconds(3),
                TTL, RETRY, new SoapProperties.MockEndpoint(false));
    }

    private void rateIs(String vndPerUsd) {
        when(client.getRate("USD", "VND")).thenReturn(new ExchangeRateSoapClient.Quote(new BigDecimal(vndPerUsd), T0));
    }

    private void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Test
    void convertsVndRevenueToUsd_withTheFetchedRate_andReportsWhenItWasFetched() {
        rateIs("25400");
        service.refreshIfDue();

        ConvertedAmountResDto usd = service.convertFromVnd(new BigDecimal("1270000")).orElseThrow();

        assertThat(usd.amount()).isEqualByComparingTo("50.00");
        assertThat(usd.amount().scale()).isEqualTo(2);
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.rate()).isEqualByComparingTo("25400");
        // Thời điểm APP lấy tỷ giá, theo giờ máy chủ (hiển thị trên dashboard)
        assertThat(usd.rateFetchedAt()).isEqualTo(LocalDateTime.ofInstant(T0, ZoneId.systemDefault()));
        // Làm tròn HALF_UP tới 2 chữ số
        assertThat(service.convertFromVnd(new BigDecimal("100000")).orElseThrow().amount()).isEqualByComparingTo("3.94");
        assertThat(service.convertFromVnd(BigDecimal.ZERO).orElseThrow().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void requests_neverCallTheSoapService_evenWhenNothingIsCachedYet() {
        // Trang dashboard chỉ đọc cache: chưa có tỷ giá → rỗng ngay, không chờ dịch vụ
        assertThat(service.convertFromVnd(new BigDecimal("1000000"))).isEmpty();
        assertThat(service.isEnabled()).isTrue();

        verifyNoInteractions(client);
    }

    @Test
    void rateIsFetchedOncePerCacheTtl_notOnEveryRefreshTick() {
        rateIs("25400");

        service.refreshIfDue();
        advance(Duration.ofMinutes(9));
        service.refreshIfDue();          // job chạy mỗi 30s nhưng chưa tới 10 phút
        verify(client, times(1)).getRate("USD", "VND");

        advance(Duration.ofMinutes(1));  // đủ cache-ttl
        rateIs("25500");
        service.refreshIfDue();

        verify(client, times(2)).getRate("USD", "VND");
        assertThat(service.convertFromVnd(new BigDecimal("25500")).orElseThrow().amount()).isEqualByComparingTo("1.00");
    }

    @Test
    void failure_keepsTheLastRate_logsWarnWithoutRemoteDetails_andBacksOff(CapturedOutput output) {
        rateIs("25400");
        service.refreshIfDue();
        advance(TTL);
        when(client.getRate("USD", "VND")).thenThrow(new ExchangeRateUnavailableException("TIMEOUT_OR_IO"));

        assertThatNoException().isThrownBy(service::refreshIfDue);

        // Giá trị cũ vẫn dùng được (còn trong 2 × cache-ttl)
        assertThat(service.convertFromVnd(new BigDecimal("25400")).orElseThrow().amount()).isEqualByComparingTo("1.00");
        assertThat(output.getOut()).contains("WARN", "Không lấy được tỷ giá", "TIMEOUT_OR_IO").doesNotContain("secret");

        // Trong retry-after-failure: không gọi lại
        advance(RETRY.minusSeconds(1));
        service.refreshIfDue();
        verify(client, times(2)).getRate("USD", "VND");
        // Hết backoff: thử lại
        advance(Duration.ofSeconds(1));
        service.refreshIfDue();
        verify(client, times(3)).getRate("USD", "VND");
    }

    @Test
    void rateOlderThanTwiceTheTtl_isNoLongerShown_untilTheServiceRecovers() {
        rateIs("25400");
        service.refreshIfDue();
        when(client.getRate("USD", "VND")).thenThrow(new ExchangeRateUnavailableException("HTTP_ERROR"));

        advance(TTL.multipliedBy(2));
        service.refreshIfDue();
        assertThat(service.convertFromVnd(BigDecimal.TEN)).as("exactly 2 × TTL old").isPresent();

        advance(Duration.ofSeconds(1));
        // Dashboard ghi "Không lấy được tỷ giá" thay vì hiện con số quá cũ
        assertThat(service.convertFromVnd(BigDecimal.TEN)).isEmpty();

        advance(RETRY);
        org.mockito.Mockito.reset(client);
        rateIs("25600");
        service.refreshIfDue();
        assertThat(service.convertFromVnd(new BigDecimal("25600")).orElseThrow().amount()).isEqualByComparingTo("1.00");
    }

    @Test
    void unexpectedClientError_isLoggedAndSwallowed_soTheJobAndThePageKeepWorking(CapturedOutput output) {
        when(client.getRate("USD", "VND")).thenThrow(new IllegalStateException("boom"));

        assertThatNoException().isThrownBy(service::refreshIfDue);

        assertThat(service.convertFromVnd(BigDecimal.TEN)).isEmpty();
        assertThat(output.getOut()).contains("ERROR", "Unexpected error while refreshing the exchange rate");
    }

    @Test
    void withoutConfiguredUrl_theFeatureIsOff_andNothingIsEverCalled() {
        ExchangeRateService disabled = new ExchangeRateService(client, properties(""), clock);

        disabled.refreshIfDue();

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.convertFromVnd(BigDecimal.TEN)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void concurrentRefresh_isSkipped_notQueued_whileOneCallIsInFlight() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.getRate("USD", "VND")).thenAnswer(invocation -> {
            inFlight.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new ExchangeRateSoapClient.Quote(new BigDecimal("25400"), T0);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> slowRefresh = executor.submit(service::refreshIfDue);
            assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue();

            // Lượt job kế tiếp (hoặc instance khởi động lại job) trong lúc lời gọi đầu chưa xong: bỏ qua ngay
            service.refreshIfDue();
            verify(client, times(1)).getRate("USD", "VND");

            release.countDown();
            slowRefresh.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(service.convertFromVnd(BigDecimal.TEN)).isPresent();
    }
}
