package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.DashboardChartResDto;
import com.huuhv.foodsndrinks.service.DashboardService;
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

    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("stats", dashboardService.getDashboardData());
        return "admin/dashboard";
    }

    /** Dữ liệu biểu đồ cho static/js/admin/dashboard.js (CSP không cho script inline). Session + ROLE_ADMIN như /admin/**. */
    @GetMapping(value = "/dashboard/charts", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DashboardChartResDto dashboardCharts() {
        return dashboardService.getChartData();
    }
}
