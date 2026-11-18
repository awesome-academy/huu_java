package com.huuhv.foodsndrinks.service.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.huuhv.foodsndrinks.logging.SafeKeyValuePairsConverter;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Gắn {@link ListAppender} vào logger {@code AUDIT} để test đọc lại các sự kiện audit đã ghi, dưới dạng
 * "message + key-value" giống hệt phần cuối của một dòng log text ({@code %safeMsg%safeKvp}).
 */
public final class AuditLogTestSupport implements AutoCloseable {

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final SafeKeyValuePairsConverter keyValues = new SafeKeyValuePairsConverter();

    public AuditLogTestSupport() {
        appender.start();
        auditLogger.addAppender(appender);
    }

    public List<String> lines() {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage() + keyValues.convert(event))
                .toList();
    }

    @Override
    public void close() {
        auditLogger.detachAppender(appender);
        appender.stop();
    }
}
