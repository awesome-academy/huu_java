package com.huuhv.foodsndrinks.logging;

/**
 * Làm sạch dữ liệu do người dùng kiểm soát trước khi đưa vào log (MDC, audit log) để chống log injection.
 * Pattern Logback đã thay {@code \r\n} trong message, nhưng giá trị MDC và các trường key=value của audit log
 * cần được làm sạch riêng: thay ký tự điều khiển và khoảng trắng bằng {@code _}, cắt về độ dài tối đa.
 */
public final class LogSanitizer {

    public static final int MAX_LENGTH = 64;

    private LogSanitizer() {
    }

    /** @return chuỗi đã làm sạch, {@code "-"} nếu {@code value} rỗng/null */
    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String cleaned = value.strip().replaceAll("[\\p{Cntrl}\\s]+", "_");
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) + "..." : cleaned;
    }
}
