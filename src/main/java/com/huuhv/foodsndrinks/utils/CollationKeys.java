package com.huuhv.foodsndrinks.utils;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Khoá so sánh chuỗi GẦN với collation mặc định của MySQL ({@code utf8mb4_0900_ai_ci}: không phân biệt hoa/thường, dấu,
 * ký tự fullwidth). Chỉ dùng để gom nhóm/phát hiện trùng phía Java — collation thật còn các quy tắc Java không tái hiện
 * được (vd. {@code ß = ss}), nên quyết định cuối cùng "hai chuỗi có bằng nhau không" luôn để câu truy vấn MySQL trả lời.
 */
public final class CollationKeys {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private CollationKeys() {
    }

    /**
     * NFKD + bỏ dấu + viết thường + {@code đ → d} (đã bỏ khoảng trắng đầu/cuối); {@code null} → chuỗi rỗng. "đ" (U+0111)
     * là chữ có gạch, không phải "d" + dấu nên NFKD không tách — nhưng collation coi {@code đ = d} ("Do uong" = "Đồ uống").
     */
    public static String of(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).replace('đ', 'd');
    }
}
