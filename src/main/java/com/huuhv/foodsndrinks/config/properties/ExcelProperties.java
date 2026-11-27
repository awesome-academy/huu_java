package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Excel import/export ({@code app.excel.*}, {@code service/excel}).
 *
 * @param export  giới hạn khi xuất file
 * @param imports giới hạn khi nhập file ({@code app.excel.import.*} — {@code import} là từ khoá Java)
 */
@Validated
@ConfigurationProperties("app.excel")
public record ExcelProperties(@DefaultValue @Valid Export export,
                              @DefaultValue @Valid @Name("import") Import imports) {

    /**
     * @param maxRows số dòng dữ liệu tối đa của một file xuất; bộ lọc khớp nhiều hơn → báo lỗi, không tạo file (một sheet
     *                .xlsx chứa tối đa 1.048.576 dòng, kể cả dòng tiêu đề)
     */
    public record Export(@Min(1) @Max(1_000_000) @DefaultValue("50000") int maxRows) {
    }

    /**
     * @param maxRows             số dòng dữ liệu tối đa của một file import (không tính dòng tiêu đề)
     * @param maxFileSize         kích thước file upload tối đa — không được lớn hơn
     *                            {@code spring.servlet.multipart.max-file-size} (kiểm tra lúc khởi động,
     *                            {@code config/ExcelConfig}), vì Tomcat đã từ chối request trước khi tới đây
     * @param maxUncompressedSize tổng dung lượng SAU KHI GIẢI NÉN của file .xlsx (là file zip) — chặn zip bomb trước khi
     *                            POI mở file
     * @param maxErrors           số lỗi dòng tối đa được giữ lại để hiển thị (tổng số lỗi vẫn được đếm đủ)
     */
    public record Import(@Min(1) @Max(100_000) @DefaultValue("1000") int maxRows,
                         @NotNull @DefaultValue("5MB") DataSize maxFileSize,
                         @NotNull @DefaultValue("20MB") DataSize maxUncompressedSize,
                         @Min(1) @Max(10_000) @DefaultValue("200") int maxErrors) {
    }
}
