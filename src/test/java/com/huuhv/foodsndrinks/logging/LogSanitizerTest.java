package com.huuhv.foodsndrinks.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "bob\r\nfake",
            "bob\u0085fake",   // NEL
            "bob fake",   // LINE SEPARATOR
            "bob fake",   // PARAGRAPH SEPARATOR
            "bob‮fake",   // RIGHT-TO-LEFT OVERRIDE (bidi)
            "bob fake",   // NO-BREAK SPACE
            "bob\tfake"
    })
    void sanitize_replacesUnicodeLineBreaksControlAndSpaces(String input) {
        assertThat(LogSanitizer.sanitize(input)).isEqualTo("bob_fake");
    }

    @Test
    void sanitize_handlesBlankAndLongValues() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("-");
        assertThat(LogSanitizer.sanitize("  ")).isEqualTo("-");
        assertThat(LogSanitizer.sanitize("x".repeat(80))).isEqualTo("x".repeat(64) + "...");
    }

    @Test
    void truncate_usesGivenLimit_andLeavesShortOrNullValuesAlone() {
        assertThat(LogSanitizer.truncate("abcdef", 3)).isEqualTo("abc...");
        assertThat(LogSanitizer.truncate("abc", 3)).isEqualTo("abc");
        assertThat(LogSanitizer.truncate(null, 3)).isNull();
    }

    @Test
    void stripLineBreaks_keepsNormalSpacesAndTabs_butRemovesEveryLineBreak() {
        String input = "user input: a b\tc\r\nFAKE FAKE2\u0085FAKE3‮";

        assertThat(LogSanitizer.stripLineBreaks(input)).isEqualTo("user input: a b\tc_FAKE_FAKE2_FAKE3_");
    }
}
