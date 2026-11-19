package com.huuhv.foodsndrinks.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dữ liệu 2 biểu đồ của dashboard admin ({@code GET /admin/dashboard/charts}), đã tách sẵn thành mảng nhãn/giá trị
 * để Chart.js dùng trực tiếp.
 *
 * @param monthLabels   nhãn 6 tháng gần nhất, vd. "Th6/2026"
 * @param monthRevenues doanh thu đơn hoàn thành của từng tháng (cùng thứ tự với {@code monthLabels})
 * @param statusLabels  nhãn trạng thái đơn (không gồm CART)
 * @param statusCounts  số đơn theo trạng thái (cùng thứ tự với {@code statusLabels})
 */
public record DashboardChartResDto(
        List<String> monthLabels,
        List<BigDecimal> monthRevenues,
        List<String> statusLabels,
        List<Long> statusCounts) {
}
