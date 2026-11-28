package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.dto.response.DashboardResDto;
import com.huuhv.foodsndrinks.service.DashboardChartCache;
import com.huuhv.foodsndrinks.service.DashboardService;
import com.huuhv.foodsndrinks.service.soap.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DashboardService dashboardService;
    private final DashboardChartCache dashboardChartCache;
    private final ExchangeRateService exchangeRateService;

    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(Model model) {
        DashboardResDto stats = dashboardService.getDashboardData();
        model.addAttribute("stats", stats);
        // Doanh thu quy đổi USD theo tỷ giá của dịch vụ SOAP. Chỉ đọc giá trị đang giữ trong bộ nhớ (job nền làm mới):
        // không gọi mạng trong request, không ném lỗi — dịch vụ tỷ giá hỏng thì trang vẫn hiển thị, revenueUsd = null
        model.addAttribute("exchangeRateEnabled", exchangeRateService.isEnabled());
        model.addAttribute("revenueUsd", exchangeRateService.convertFromVnd(stats.getTotalRevenue()).orElse(null));
        return "admin/dashboard";
    }

    /** Dữ liệu biểu đồ cho static/js/admin/dashboard.js (CSP không cho script inline). Session + ROLE_ADMIN như /admin/**. */
    @GetMapping(value = "/dashboard/charts", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DashboardChartResDto dashboardCharts() {
        return dashboardChartCache.get();
    }
}
