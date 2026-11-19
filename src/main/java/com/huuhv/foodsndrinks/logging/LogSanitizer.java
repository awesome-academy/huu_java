package com.huuhv.foodsndrinks.logging;

import java.util.regex.Pattern;

/**
 * Làm sạch dữ liệu do người dùng kiểm soát trước khi đưa vào log để chống log injection (giả mạo dòng log).
 * <p>
 * Bao phủ cả ký tự ngắt dòng Unicode mà {@code \p{Cntrl}}/{@code \s} mặc định (chỉ ASCII) bỏ sót: NEL U+0085,
 * LINE/PARAGRAPH SEPARATOR U+2028/U+2029, và ký tự định dạng vô hình {@code \p{Cf}} như bidi override U+202E.
 * Pattern được compile một lần vì được gọi trên mọi request đã đăng nhập (MDC {@code user}) và mọi dòng log.
 */
public final class LogSanitizer {

    public static final int MAX_LENGTH = 64;

    /** Ký tự điều khiển (trừ tab), ký tự định dạng vô hình và ký tự ngắt dòng Unicode — dùng cho message/exception. */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}&&[^\\t]]+");

    /** Như trên, cộng mọi loại khoảng trắng (kể cả NBSP) — dùng cho một giá trị đơn như username trong MDC. */
    private static final Pattern UNSAFE_IN_VALUE =
            Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    private LogSanitizer() {
    }

    /**
     * Làm sạch một giá trị đơn (vd. username trong MDC): thay ký tự điều khiển/ngắt dòng/khoảng trắng bằng
     * {@code _}, cắt về {@value #MAX_LENGTH} ký tự.
     *
     * @return chuỗi đã làm sạch, {@code "-"} nếu {@code value} rỗng/null
     */
    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return truncate(UNSAFE_IN_VALUE.matcher(value.strip()).replaceAll("_"));
    }

    /**
     * Thay ký tự ngắt dòng/điều khiển/định dạng vô hình trong một đoạn text tự do (message, exception message) bằng
     * {@code _}, giữ nguyên khoảng trắng thường để log vẫn dễ đọc.
     */
    public static String stripLineBreaks(String text) {
        if (text == null) {
            return null;
        }
        return LINE_BREAKS.matcher(text).replaceAll("_");
    }

    /** Cắt chuỗi về {@value #MAX_LENGTH} ký tự để dữ liệu người dùng không làm phình log. */
    public static String truncate(String value) {
        return truncate(value, MAX_LENGTH);
    }

    /** Cắt chuỗi về {@code maxLength} ký tự, thêm {@code ...} nếu có cắt. */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
