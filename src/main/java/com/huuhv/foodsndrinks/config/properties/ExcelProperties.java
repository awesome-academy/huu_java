package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Excel import/export ({@code app.excel.*}, {@code service/excel}).
 *
 * @param export giới hạn khi xuất file
 */
@Validated
@ConfigurationProperties("app.excel")
public record ExcelProperties(@DefaultValue @Valid Export export) {

    /**
     * @param maxRows số dòng dữ liệu tối đa của một file xuất; bộ lọc khớp nhiều hơn → báo lỗi, không tạo file (một sheet
     *                .xlsx chứa tối đa 1.048.576 dòng, kể cả dòng tiêu đề)
     */
    public record Export(@Min(1) @Max(1_000_000) @DefaultValue("50000") int maxRows) {
    }
}
