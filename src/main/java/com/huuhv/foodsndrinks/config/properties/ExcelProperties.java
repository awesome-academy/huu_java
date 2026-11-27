package com.huuhv.foodsndrinks.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

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
     * @param dir                 thư mục giữ file .xlsx đang chờ xử lý ({@code service/excel/ImportFileStorage}). Không
     *                            được nằm trong {@code app.upload.dir} (thư mục đó được phục vụ public tại
     *                            {@code /uploads/**}). Chạy nhiều instance: mọi instance phải trỏ tới CÙNG một thư mục
     *                            dùng chung — instance nhận upload và instance xử lý message có thể khác nhau
     * @param maxActiveJobsPerUser số lần import chưa xong tối đa của một admin; vượt thì upload mới bị từ chối
     * @param staleAfter          lần import chưa kết thúc sau khoảng này bị coi là kẹt và đánh dấu thất bại; file tạm cũ
     *                            hơn 2 lần khoảng này bị xoá ({@code ImportJobCleanupJob})
     */
    public record Import(@Min(1) @Max(100_000) @DefaultValue("1000") int maxRows,
                         @NotNull @DefaultValue("5MB") DataSize maxFileSize,
                         @NotNull @DefaultValue("20MB") DataSize maxUncompressedSize,
                         @Min(1) @Max(10_000) @DefaultValue("200") int maxErrors,
                         @NotBlank @DefaultValue("./import-tmp") String dir,
                         @Min(1) @Max(100) @DefaultValue("3") int maxActiveJobsPerUser,
                         @NotNull @DurationMin(minutes = 1) @DefaultValue("30m") Duration staleAfter) {

        /** Đường dẫn tuyệt đối, đã chuẩn hoá của {@link #dir}. */
        public Path directory() {
            return Path.of(dir).toAbsolutePath().normalize();
        }
    }
}
