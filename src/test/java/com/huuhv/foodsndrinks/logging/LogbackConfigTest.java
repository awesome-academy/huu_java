package com.huuhv.foodsndrinks.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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

    private static final String INSTANCE_ID = "node-1";

    private static MockEnvironment environment(Path logDir, String... profiles) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.file.path", logDir.toString())
                .withProperty("app.instance-id", INSTANCE_ID)
                .withProperty("logging.structured.ecs.service.node-name", INSTANCE_ID);
        environment.setActiveProfiles(profiles);
        return environment;
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

    private static Logger root() {
        return context().getLogger(Logger.ROOT_LOGGER_NAME);
    }

    @SuppressWarnings("unchecked")
    private static String encode(String appenderName, Logger logger, LoggingEvent event) {
        var appender = (OutputStreamAppender<ILoggingEvent>) logger.getAppender(appenderName);
        return new String(appender.getEncoder().encode(event), StandardCharsets.UTF_8);
    }

    private static LoggingEvent event(Logger logger, Level level, String message, Throwable error, Object... args) {
        return new LoggingEvent(LogbackConfigTest.class.getName(), logger, level, message, error, args);
    }

    // -------------------------------------------------------
    // Pattern text (console / foodsndrinks.log)
    // -------------------------------------------------------

    @Test
    void consolePattern_stripsAllLineBreaksFromMessage_andPrintsMdc() {
        initialize(new MockEnvironment().withProperty("app.instance-id", INSTANCE_ID));
        MDC.setContextMap(Map.of(RequestIdFilter.MDC_KEY, "req-7", MdcUserFilter.MDC_KEY, "alice"));

        String line = encode("CONSOLE", root(), event(root(), Level.INFO, "user input: {}", null,
                "line1\r\nFAKE ERROR line2\u0085line3‮"));

        assertThat(line).contains("[node-1] [req-7] [alice]");
        assertThat(line).contains("user input: line1_FAKE ERROR_line2_line3_");
        assertThat(line.strip()).doesNotContain("\n").doesNotContain("\r").doesNotContain(" ");
    }

    @Test
    void consolePattern_showsClientRequestIdAsCid_onlyWhenPresent() {
        initialize(new MockEnvironment());

        MDC.setContextMap(Map.of(RequestIdFilter.MDC_KEY, "req-1", RequestIdFilter.CLIENT_MDC_KEY, "client-9"));
        String withClientId = encode("CONSOLE", root(), event(root(), Level.INFO, "hello", null));
        MDC.setContextMap(Map.of(RequestIdFilter.MDC_KEY, "req-2"));
        String withoutClientId = encode("CONSOLE", root(), event(root(), Level.INFO, "hello", null));

        assertThat(withClientId).contains("[req-1 cid=client-9] []");
        assertThat(withoutClientId).contains("[req-2] []").doesNotContain("cid=");
    }

    @Test
    void consolePattern_sanitizesExceptionMessage_butKeepsStackTraceLines() {
        initialize(new MockEnvironment());
        var cause = new IllegalStateException("cause\r\n2026-01-01T00:00:00.000+07:00  INFO fake cause line");
        var error = new IllegalArgumentException("Failed to convert value \"1\r\n2026-01-01T00:00:00.000+07:00"
                + "  INFO [main] [] [] AUDIT : forged\"", cause);

        String output = encode("CONSOLE", root(), event(root(), Level.ERROR, "request failed", error));

        assertThat(output).contains("java.lang.IllegalArgumentException: Failed to convert value \"1_2026-01-01");
        assertThat(output).contains("Caused by: java.lang.IllegalStateException: cause_2026-01-01");
        assertThat(output.lines()).noneMatch(line -> line.startsWith("2026-01-01"));
        assertThat(output.lines()).anyMatch(line -> line.strip().startsWith("at com.huuhv.foodsndrinks"));
    }

    @Test
    void consolePattern_printsAuditKeyValuesAsLogfmt() {
        initialize(new MockEnvironment());
        LoggingEvent event = event(root(), Level.WARN, "Login failed", null);
        event.setKeyValuePairs(List.of(
                new KeyValuePair("event.action", "LOGIN_FAILURE"),
                new KeyValuePair("username", "a=b \"x\"@x.com\r\nfake=1")));

        String line = encode("CONSOLE", root(), event);

        assertThat(line).contains(": Login failed event.action=LOGIN_FAILURE username=\"a=b \\\"x\\\"@x.com_fake=1\"");
    }

    // -------------------------------------------------------
    // Appender theo profile
    // -------------------------------------------------------

    @Test
    void devProfile_writesConsoleAndAsyncMainFileInLogDir_butNoAuditFile(@TempDir Path logDir) {
        initialize(environment(logDir, "dev"));

        assertThat(root().getAppender("CONSOLE")).isNotNull();
        assertThat(root().getAppender("ASYNC_FILE")).isInstanceOfSatisfying(AsyncAppender.class, async -> {
            assertThat(async.getDiscardingThreshold()).isZero();
            assertThat(async.getAppender("FILE")).isInstanceOfSatisfying(RollingFileAppender.class, file ->
                    assertThat(Path.of(file.getFile())).isEqualTo(logDir.resolve("foodsndrinks.log")));
        });
        assertThat(context().getLogger("AUDIT").getAppender("AUDIT_FILE")).isNull();
    }

    @Test
    void testProfile_logsToConsoleOnly_evenWithDev(@TempDir Path logDir) {
        initialize(environment(logDir, "dev", "test"));

        assertThat(root().getAppender("CONSOLE")).isNotNull();
        assertThat(root().getAppender("ASYNC_FILE")).isNull();
    }

    @Test
    void prodProfile_writesFilesInPerInstanceDir_andAuditFileAsEcsJson(@TempDir Path logDir) {
        initialize(environment(logDir, "prod"));

        // Nhiều instance dùng chung thư mục log (vd. bind mount) → mỗi instance một thư mục con, không ghi chung file
        Path instanceDir = logDir.resolve(INSTANCE_ID);
        assertThat(root().getAppender("ASYNC_FILE")).isInstanceOfSatisfying(AsyncAppender.class, async ->
                assertThat(async.getAppender("FILE")).isInstanceOfSatisfying(RollingFileAppender.class, file ->
                        assertThat(Path.of(file.getFile())).isEqualTo(instanceDir.resolve("foodsndrinks.log"))));
        Logger audit = context().getLogger("AUDIT");
        assertThat(audit.getLevel()).isEqualTo(Level.INFO);
        assertThat(audit.getAppender("AUDIT_FILE")).isInstanceOfSatisfying(RollingFileAppender.class, file ->
                assertThat(Path.of(file.getFile())).isEqualTo(instanceDir.resolve("audit.log")));

        // Key MDC (requestId, clientRequestId, user.name) và key audit không được trùng — trùng thì encoder ECS báo lỗi
        MDC.setContextMap(Map.of(RequestIdFilter.MDC_KEY, "req-7", RequestIdFilter.CLIENT_MDC_KEY, "client-1",
                MdcUserFilter.MDC_KEY, "admin"));
        LoggingEvent event = event(audit, Level.WARN, "Login failed", null);
        event.setKeyValuePairs(List.of(
                new KeyValuePair("event.action", "LOGIN_FAILURE"),
                new KeyValuePair("event.outcome", "failure"),
                new KeyValuePair("username", "a=b\"x\"@x.com\r\nfake"),
                new KeyValuePair("target.id", 5L),
                new KeyValuePair("source.ip", "127.0.0.1")));

        String json = encode("AUDIT_FILE", audit, event);

        assertThat(json.strip()).startsWith("{").endsWith("}").doesNotContain("\n");
        assertThat(json)
                .contains("\"message\":\"Login failed\"")
                .contains("\"event\":{\"action\":\"LOGIN_FAILURE\",\"outcome\":\"failure\"}")
                .contains("\"username\":\"a=b\\\"x\\\"@x.com\\r\\nfake\"")
                .contains("\"target\":{\"id\":5}")
                .contains("\"requestId\":\"req-7\"")
                .contains("\"clientRequestId\":\"client-1\"")
                // MDC user.name lồng thành object theo ECS — chuỗi phẳng "user":"admin" bị mapping ECS từ chối
                .contains("\"user\":{\"name\":\"admin\"}")
                .contains("\"node\":{\"name\":\"node-1\"}");
    }
}
