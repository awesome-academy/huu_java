package com.huuhv.foodsndrinks.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MdcScopeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keysAreSetInsideScope_andRemovedOnClose_whenAbsentBefore() {
        try (MdcScope ignored = MdcScope.open().put("a", "1").put("b", "2")) {
            assertThat(MDC.get("a")).isEqualTo("1");
            assertThat(MDC.get("b")).isEqualTo("2");
        }

        assertThat(MDC.get("a")).isNull();
        assertThat(MDC.get("b")).isNull();
    }

    @Test
    void previousValueIsRestoredOnClose_unlikeMdcPutCloseable() {
        MDC.put("a", "outer");

        try (MdcScope ignored = MdcScope.open().put("a", "inner")) {
            assertThat(MDC.get("a")).isEqualTo("inner");
        }

        assertThat(MDC.get("a")).isEqualTo("outer");
    }

    @Test
    void nullValue_removesKeyInsideScope_andRestoresItOnClose() {
        MDC.put("a", "outer");

        try (MdcScope ignored = MdcScope.open().put("a", null)) {
            assertThat(MDC.get("a")).isNull();
        }

        assertThat(MDC.get("a")).isEqualTo("outer");
    }

    @Test
    void sameKeyPutTwice_restoresValueFromBeforeTheScope() {
        MDC.put("a", "outer");

        try (MdcScope ignored = MdcScope.open().put("a", "first").put("a", "second")) {
            assertThat(MDC.get("a")).isEqualTo("second");
        }

        assertThat(MDC.get("a")).isEqualTo("outer");
    }

    @Test
    void keysNotPut_areLeftUntouched() {
        MDC.put("other", "kept");

        try (MdcScope ignored = MdcScope.open().put("a", "1")) {
            MDC.put("other", "changed-inside");
        }

        assertThat(MDC.get("other")).isEqualTo("changed-inside");
    }

    @Test
    void scopeIsClosed_whenBodyThrows() {
        try (MdcScope ignored = MdcScope.open().put("a", "1")) {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException expected) {
            // expected
        }

        assertThat(MDC.get("a")).isNull();
    }
}
