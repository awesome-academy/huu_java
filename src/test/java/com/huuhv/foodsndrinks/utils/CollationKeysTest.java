package com.huuhv.foodsndrinks.utils;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

class CollationKeysTest {

    @Test
    void ignoresCase_accents_fullwidthForms_andSurroundingSpaces() {
        assertThat(CollationKeys.of("  Bò Kho ")).isEqualTo("bo kho");
        assertThat(CollationKeys.of("Bơ khô")).isEqualTo("bo kho");
        assertThat(CollationKeys.of("ＡＤＭＩＮ")).isEqualTo("admin");
        assertThat(CollationKeys.of(null)).isEmpty();
    }

    @Test
    void sameTextInComposedAndDecomposedUnicode_hasTheSameKey() {
        String composed = "Trà sữa trân châu";
        assertThat(CollationKeys.of(Normalizer.normalize(composed, Normalizer.Form.NFD)))
                .isEqualTo(CollationKeys.of(composed));
    }
}
