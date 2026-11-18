package com.huuhv.foodsndrinks.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Nạp {@code logback-spring.xml} thật qua LoggingSystem của Spring Boot (cần để xử lý {@code <springProfile>}). */
class LogbackConfigTest {

    private static final String CONFIG = "classpath:logback-spring.xml";

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    private void initialize(MockEnvironment environment) {
        loggingSystem.cleanUp(); // bỏ đánh dấu "đã khởi tạo" để nạp lại cấu hình
        loggingSystem.beforeInitialize();
        // Như LoggingApplicationListener: LogFile mang logging.file.path → system property LOG_PATH
        loggingSystem.initialize(new LoggingInitializationContext(environment), CONFIG, LogFile.get(environment));
    }

    @AfterEach
    void restoreDefaultConfig() {
        MDC.clear();
        System.clearProperty(LoggingSystemProperty.LOG_PATH.getEnvironmentVariableName());
        initialize(new MockEnvironment());
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @Test
    void consolePattern_stripsLineBreaks_andPrintsRequestIdAndUser() {
        initialize(new MockEnvironment());
        Logger root = context().getLogger(Logger.ROOT_LOGGER_NAME);
        var console = (OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>) root.getAppender("CONSOLE");
        assertThat(console).isNotNull();
        assertThat(root.getAppender("FILE")).isNull();

        MDC.setContextMap(Map.of(RequestIdFilter.MDC_KEY, "req-7", MdcUserFilter.MDC_KEY, "alice"));
        var event = new LoggingEvent(getClass().getName(), root, Level.INFO,
                "user input: {}", null, new Object[]{"line1\r\nFAKE ERROR line2\nline3"});
        String line = new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);

        assertThat(line).contains("[req-7] [alice]");
        assertThat(line).contains("user input: line1_FAKE ERROR line2_line3");
        // Chỉ còn đúng 1 ký tự xuống dòng ở cuối (do %n)
        assertThat(line.strip()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void devProfile_writesConsoleAndMainFile_butNoAuditFile(@TempDir Path logDir) {
        MockEnvironment dev = new MockEnvironment().withProperty("logging.file.path", logDir.toString());
        dev.setActiveProfiles("dev");
        initialize(dev);

        Logger root = context().getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender("CONSOLE")).isNotNull();
        assertThat(root.getAppender("FILE")).isInstanceOfSatisfying(RollingFileAppender.class, file ->
                assertThat(Path.of(file.getFile())).isEqualTo(logDir.resolve("foodsndrinks.log")));
        assertThat(context().getLogger("AUDIT").getAppender("AUDIT_FILE")).isNull();
    }

    @Test
    void prodProfile_writesRollingMainAndAuditFiles(@TempDir Path logDir) {
        MockEnvironment prod = new MockEnvironment().withProperty("logging.file.path", logDir.toString());
        prod.setActiveProfiles("prod");
        initialize(prod);

        Logger root = context().getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender("CONSOLE")).isNotNull();
        assertThat(root.getAppender("FILE")).isInstanceOfSatisfying(RollingFileAppender.class, file ->
                assertThat(Path.of(file.getFile())).isEqualTo(logDir.resolve("foodsndrinks.log")));

        Logger audit = context().getLogger("AUDIT");
        assertThat(audit.getLevel()).isEqualTo(Level.INFO);
        assertThat(audit.getAppender("AUDIT_FILE")).isInstanceOfSatisfying(RollingFileAppender.class, file ->
                assertThat(Path.of(file.getFile())).isEqualTo(logDir.resolve("audit.log")));
    }
}
