package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.ProductType;

import java.math.BigDecimal;

/**
 * Sản phẩm đã có mà MySQL coi là trùng tên với một dòng của file import — projection ({@code SELECT new}) của
 * {@code ProductRepository.findImportMatchesByName}. Mang đủ giá trị hiện tại để biết dòng import có đổi gì không, không
 * nạp entity (bước validate chạy cả trên thread không có session Hibernate).
 */
public record ProductImportMatch(Long id, String name, Long categoryId, ProductType type, BigDecimal price,
                                 String description, Boolean isAvailable) {
}
