package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExcelConfig}: binding {@code app.excel.*} và kiểm tra giới hạn file import so với giới hạn multipart. */
class ExcelConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExcelConfig.class)
            .withPropertyValues("spring.servlet.multipart.max-file-size=5MB");

    @Test
    void defaults_matchTheDocumentedLimits() {
        runner.run(context -> {
            ExcelProperties properties = context.getBean(ExcelProperties.class);
            assertThat(properties.export().maxRows()).isEqualTo(50_000);
            assertThat(properties.imports().maxRows()).isEqualTo(1_000);
            assertThat(properties.imports().maxFileSize()).isEqualTo(DataSize.ofMegabytes(5));
            assertThat(properties.imports().maxUncompressedSize()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(properties.imports().maxErrors()).isEqualTo(200);
        });
    }

    @Test
    void importSection_isBoundFromTheImportKey() {
        runner.withPropertyValues("app.excel.import.max-rows=250", "app.excel.import.max-file-size=2MB",
                        "app.excel.export.max-rows=10")
                .run(context -> {
                    ExcelProperties properties = context.getBean(ExcelProperties.class);
                    assertThat(properties.imports().maxRows()).isEqualTo(250);
                    assertThat(properties.imports().maxFileSize()).isEqualTo(DataSize.ofMegabytes(2));
                    assertThat(properties.export().maxRows()).isEqualTo(10);
                });
    }

    @Test
    void importLimitAboveTheMultipartLimit_failsStartup_becauseItCouldNeverTakeEffect() {
        runner.withPropertyValues("app.excel.import.max-file-size=6MB")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("app.excel.import.max-file-size (6291456 bytes) must not exceed "
                                + "spring.servlet.multipart.max-file-size (5242880 bytes)"));
        // Mặc định của Boot (1MB) khi không khai báo giới hạn multipart
        new ApplicationContextRunner().withUserConfiguration(ExcelConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void outOfRangeValues_failStartup() {
        runner.withPropertyValues("app.excel.import.max-rows=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.excel.export.max-rows=2000000")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.excel.import.max-errors=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
