package com.huuhv.foodsndrinks.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * {@code %safeMsg} trong logback-spring.xml: như {@code %msg} nhưng thay mọi ký tự ngắt dòng (kể cả Unicode
 * U+0085/U+2028/U+2029) và ký tự điều khiển/định dạng vô hình bằng {@code _} — xem {@link LogSanitizer}.
 */
public class SafeMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogSanitizer.stripLineBreaks(super.convert(event));
    }
}
