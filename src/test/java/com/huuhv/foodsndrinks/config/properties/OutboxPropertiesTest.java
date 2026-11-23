package com.huuhv.foodsndrinks.config.properties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class OutboxPropertiesTest {

    @Test
    void retryDelayLongerThanPollInterval_isRejected() {
        // Lần thử sau sẽ không rơi vào chu kỳ kế tiếp của job → broker sống lại mà bản ghi vẫn chờ thêm
        assertThatIllegalArgumentException().isThrownBy(() ->
                        new OutboxProperties(Duration.ofMinutes(1), Duration.ofMinutes(2), 5, 100, Duration.ofDays(7)))
                .withMessageContaining("retry-delay");
    }

    @Test
    void retryDelayUpToPollInterval_isAccepted() {
        assertThatNoException().isThrownBy(() ->
                new OutboxProperties(Duration.ofMinutes(1), Duration.ofMinutes(1), 5, 100, Duration.ofDays(7)));
        assertThatNoException().isThrownBy(() ->
                new OutboxProperties(Duration.ofHours(24), Duration.ZERO, 5, 100, Duration.ofDays(7)));
    }
}
