package com.huuhv.foodsndrinks.dto.request;

import com.huuhv.foodsndrinks.enums.ProductType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductReqDto {

    /** Giới hạn theo cột DB ({@code products.name VARCHAR(255)}). */
    public static final int NAME_MAX_LENGTH = 255;
    /**
     * Cột {@code description} là TEXT (65.535 byte; một ký tự UTF-8 tối đa 4 byte) và một ô Excel chứa tối đa 32.767 ký
     * tự — giới hạn này vừa cả hai, nên mô tả nào lưu được cũng export/import lại được.
     */
    public static final int DESCRIPTION_MAX_LENGTH = 16_000;

    private Long id;

    @NotBlank(message = "Tên sản phẩm không được để trống!")
    @Size(max = NAME_MAX_LENGTH, message = "Tên sản phẩm tối đa 255 ký tự!")
    private String name;

    @NotNull(message = "Vui lòng chọn danh mục!")
    private Long categoryId;

    @NotNull(message = "Vui lòng chọn loại sản phẩm!")
    private ProductType type;

    /** Cột {@code price DECIMAL(10,2)}: tối đa 8 chữ số phần nguyên, 2 chữ số thập phân. */
    @NotNull(message = "Vui lòng nhập giá sản phẩm!")
    @DecimalMin(value = "0.01", message = "Giá phải lớn hơn 0!")
    @Digits(integer = 8, fraction = 2, message = "Giá tối đa 99.999.999,99 và không quá 2 chữ số thập phân!")
    private BigDecimal price;

    @Size(max = DESCRIPTION_MAX_LENGTH, message = "Mô tả tối đa 16.000 ký tự!")
    private String description;

    private Boolean isAvailable = true;
}

