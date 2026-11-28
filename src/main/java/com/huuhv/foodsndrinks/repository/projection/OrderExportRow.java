package com.huuhv.foodsndrinks.repository.projection;

import com.huuhv.foodsndrinks.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Một dòng của file export đơn hàng — projection ({@code SELECT new}) của {@code OrderRepository.findExportRows}, không
 * nạp entity.
 *
 * @param itemCount tổng số lượng món của đơn (cộng {@code quantity} các dòng chi tiết)
 */
public record OrderExportRow(Long id, String customerFullName, String customerUsername, LocalDateTime orderedAt,
                             OrderStatus status, BigDecimal totalPrice, Long itemCount) {
}
