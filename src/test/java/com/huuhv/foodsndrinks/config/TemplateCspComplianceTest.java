package com.huuhv.foodsndrinks.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chặn tái phạm Content-Security-Policy ({@code script-src 'self'}): template không được có script inline, handler
 * {@code on*} (kể cả {@code th:on*}) hay URL {@code javascript:} — những thứ này bị trình duyệt chặn khi chạy thật.
 * JS mới đặt trong {@code static/js/**}, gắn sự kiện bằng {@code addEventListener}, dữ liệu qua {@code data-*}/JSON.
 */
class TemplateCspComplianceTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** Thuộc tính on...= trong thẻ (onclick, th:onclick, onsubmit...). */
    private static final Pattern EVENT_HANDLER_ATTRIBUTE =
            Pattern.compile("<[^>]*\\s(?:th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    /** Thẻ <script> không có src (script inline). */
    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?![^>]*\\b(?:th:)?src\\s*=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVASCRIPT_URL = Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE);
    /** Thư viện bên ngoài phải qua WebJars (CSP không cho host khác). */
    private static final Pattern EXTERNAL_SCRIPT_OR_STYLE =
            Pattern.compile("<(?:script|link)[^>]*\\s(?:src|href)\\s*=\\s*\"https?://", Pattern.CASE_INSENSITIVE);

    @Test
    void templates_haveNoInlineScriptsEventHandlersOrExternalAssets() {
        List<String> violations = new ArrayList<>();
        for (Path template : templates()) {
            String html = read(template);
            collect(violations, template, html, EVENT_HANDLER_ATTRIBUTE, "handler on*");
            collect(violations, template, html, INLINE_SCRIPT, "script inline");
            collect(violations, template, html, JAVASCRIPT_URL, "URL javascript:");
            collect(violations, template, html, EXTERNAL_SCRIPT_OR_STYLE, "script/CSS từ host ngoài (dùng WebJars)");
        }

        assertThat(violations).as("Vi phạm CSP trong template").isEmpty();
    }

    @Test
    void scanner_detectsViolations() {
        assertThat(EVENT_HANDLER_ATTRIBUTE.matcher("<button onclick=\"x()\">").find()).isTrue();
        assertThat(EVENT_HANDLER_ATTRIBUTE.matcher("<img th:onerror=\"x\">").find()).isTrue();
        assertThat(EVENT_HANDLER_ATTRIBUTE.matcher("<meta th:content=\"x\"><option value=\"1\">").find()).isFalse();
        assertThat(INLINE_SCRIPT.matcher("<script th:inline=\"javascript\">").find()).isTrue();
        assertThat(INLINE_SCRIPT.matcher("<script th:src=\"@{/js/a.js}\"></script>").find()).isFalse();
        assertThat(EXTERNAL_SCRIPT_OR_STYLE.matcher("<link href=\"https://cdn.example.com/a.css\">").find()).isTrue();
    }

    private static void collect(List<String> violations, Path template, String html, Pattern pattern, String kind) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            long line = html.substring(0, matcher.start()).lines().count();
            violations.add(TEMPLATES.relativize(template) + ":" + Math.max(line, 1) + " " + kind + ": " + matcher.group());
        }
    }

    private static List<Path> templates() {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            List<Path> html = files.filter(path -> path.toString().endsWith(".html")).toList();
            assertThat(html).as("không tìm thấy template trong %s", TEMPLATES.toAbsolutePath()).isNotEmpty();
            return html;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
