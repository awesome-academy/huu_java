package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.ProductType;

import java.math.BigDecimal;

/**
 * Một dòng của file export sản phẩm — projection ({@code SELECT new}) của {@code ProductRepository.findExportRows}, không
 * nạp entity. Đúng các cột của file mẫu import.
 *
 * @param categoryName {@code null} khi sản phẩm không còn danh mục (danh mục đã bị xoá)
 */
public record ProductExportRow(String name, String categoryName, ProductType type, BigDecimal price, String description,
                               Boolean isAvailable) {
}
