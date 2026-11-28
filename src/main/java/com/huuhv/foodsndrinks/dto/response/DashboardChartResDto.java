package com.huuhv.foodsndrinks.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dữ liệu 3 biểu đồ của dashboard admin ({@code GET /admin/dashboard/charts}), đã tách sẵn thành mảng nhãn/giá trị
 * để Chart.js dùng trực tiếp, kèm KPI đổi theo sự kiện đơn hàng. {@code dashboard.js} gọi lúc tải trang và mỗi lần có
 * sự kiện đơn hàng realtime. Hợp đồng JSON: chỉ thêm field, không đổi/xoá.
 *
 * @param monthLabels          nhãn 6 tháng gần nhất, vd. "Th6/2026"
 * @param monthRevenues        doanh thu đơn hoàn thành của từng tháng (cùng thứ tự với {@code monthLabels})
 * @param statusLabels         nhãn trạng thái đơn (không gồm CART)
 * @param statusCounts         số đơn theo trạng thái (cùng thứ tự với {@code statusLabels})
 * @param topProductLabels     tên tối đa 5 sản phẩm bán chạy nhất (theo số lượng, chỉ đơn hoàn thành), giảm dần
 * @param topProductQuantities số lượng đã bán (cùng thứ tự với {@code topProductLabels})
 * @param activeOrders         KPI "Đơn đang xử lý" = Chờ xác nhận + Đang xử lý, tính từ cùng kết quả đếm với
 *                             {@code statusCounts} (luôn khớp biểu đồ trạng thái)
 */
public record DashboardChartResDto(
        List<String> monthLabels,
        List<BigDecimal> monthRevenues,
        List<String> statusLabels,
        List<Long> statusCounts,
        List<String> topProductLabels,
        List<Long> topProductQuantities,
        long activeOrders) {
}
