package com.huuhv.foodsndrinks.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code %safeKvp} trong logback-spring.xml: in các key-value gắn bằng SLF4J fluent API
 * ({@code log.atInfo().addKeyValue(k, v)}) theo kiểu logfmt, vd. {@code  event.action=LOGIN_FAILURE user="a=b@x.com"}.
 * <p>
 * Giá trị chứa khoảng trắng, {@code =}, {@code "} hoặc {@code \} (hoặc rỗng) được đặt trong nháy kép và escape, nên
 * grep/parser key=value không tách field sai; ký tự ngắt dòng bị thay bằng {@code _} như {@link SafeMessageConverter}.
 * Không có key-value nào thì in chuỗi rỗng. File {@code audit.log} của prod dùng JSON (ECS) nên không qua converter này.
 */
public class SafeKeyValuePairsConverter extends ClassicConverter {

    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\\s=\"\\\\]", Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public String convert(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null || pairs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KeyValuePair pair : pairs) {
            sb.append(' ').append(LogSanitizer.sanitize(pair.key)).append('=').append(formatValue(pair.value));
        }
        return sb.toString();
    }

    static String formatValue(Object value) {
        String text = value == null ? "" : LogSanitizer.stripLineBreaks(String.valueOf(value));
        if (text.isEmpty() || NEEDS_QUOTES.matcher(text).find()) {
            return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return text;
    }
}
