package com.huuhv.foodsndrinks.service.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Gắn {@link ListAppender} vào logger {@code AUDIT} để test đọc lại các dòng audit đã ghi. */
public final class AuditLogTestSupport implements AutoCloseable {

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public AuditLogTestSupport() {
        appender.start();
        auditLogger.addAppender(appender);
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        auditLogger.detachAppender(appender);
        appender.stop();
    }
}
