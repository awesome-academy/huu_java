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
    void dWithStroke_isTheSameLetterAsD_likeTheMySqlCollation() {
        // "đ" không phải "d" + dấu nên NFKD không tách — phải thay riêng
        assertThat(CollationKeys.of("Đồ uống")).isEqualTo("do uong").isEqualTo(CollationKeys.of("Do uong"));
        assertThat(CollationKeys.of("Đậu đỏ")).isEqualTo(CollationKeys.of("dau do"));
    }

    @Test
    void sameTextInComposedAndDecomposedUnicode_hasTheSameKey() {
        String composed = "Trà sữa trân châu";
        assertThat(CollationKeys.of(Normalizer.normalize(composed, Normalizer.Form.NFD)))
                .isEqualTo(CollationKeys.of(composed));
    }
}
