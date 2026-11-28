package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.ConvertedAmountResDto;
import com.huuhv.foodsndrinks.dto.response.DashboardResDto;
import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.service.DashboardChartCache;
import com.huuhv.foodsndrinks.service.DashboardService;
import com.huuhv.foodsndrinks.service.soap.ExchangeRateService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Trang dashboard {@code /admin}: chỉ {@code ROLE_ADMIN}; truy vấn thống kê lỗi/quá thời gian → 503 thông báo chung. */
@SecuredWebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DashboardService dashboardService;
    @MockitoBean private DashboardChartCache dashboardChartCache;
    @MockitoBean private ExchangeRateService exchangeRateService;

    private static DashboardResDto stats(String totalRevenue) {
        return DashboardResDto.builder()
                .totalRevenue(new BigDecimal(totalRevenue))
                .totalCompletedOrders(3)
                .statusCounts(Map.of())
                .recentOrders(List.of())
                .build();
    }

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verifyNoInteractions(dashboardService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_isForbidden() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
        verifyNoInteractions(dashboardService);
    }

    // -------------------------------------------------------
    // Doanh thu quy đổi USD (tỷ giá từ dịch vụ SOAP — issue #30)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void revenueCard_showsUsdAmount_rate_andWhenTheRateWasFetched() throws Exception {
        when(dashboardService.getDashboardData()).thenReturn(stats("2540000"));
        when(exchangeRateService.isEnabled()).thenReturn(true);
        when(exchangeRateService.convertFromVnd(new BigDecimal("2540000"))).thenReturn(Optional.of(
                new ConvertedAmountResDto(new BigDecimal("1234.50"), "USD", new BigDecimal("25400.123456"),
                        LocalDateTime.of(2026, 11, 28, 9, 5))));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("2.540.000 đ")))
                .andExpect(content().string(containsString("≈ 1,234.50 USD")))
                .andExpect(content().string(containsString("Tỷ giá 1 USD = 25.400 đ, lấy lúc 09:05 28/11")))
                .andExpect(content().string(not(containsString("Không lấy được tỷ giá"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void exchangeRateServiceDown_dashboardStillRenders_andSaysTheRateIsUnavailable() throws Exception {
        when(dashboardService.getDashboardData()).thenReturn(stats("2540000"));
        when(exchangeRateService.isEnabled()).thenReturn(true);
        when(exchangeRateService.convertFromVnd(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2.540.000 đ")))
                .andExpect(content().string(containsString("Không lấy được tỷ giá")))
                .andExpect(content().string(not(containsString("USD"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void exchangeRateNotConfigured_dashboardDoesNotMentionIt() throws Exception {
        when(dashboardService.getDashboardData()).thenReturn(stats("2540000"));
        when(exchangeRateService.isEnabled()).thenReturn(false);
        when(exchangeRateService.convertFromVnd(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2.540.000 đ")))
                .andExpect(content().string(not(containsString("Không lấy được tỷ giá"))))
                .andExpect(content().string(not(containsString("USD"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_neverTriggersTheExchangeRateLookup() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isForbidden());

        verifyNoInteractions(exchangeRateService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboardUnavailable_returns503_withGenericMessage() throws Exception {
        when(dashboardService.getDashboardData()).thenThrow(new DashboardUnavailableException(
                "Dashboard queries timed out", new TimeoutException("internal detail")));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error/admin/500"))
                .andExpect(model().attribute("message", "Không tải được số liệu dashboard, vui lòng thử lại sau."))
                .andExpect(content().string(not(containsString("internal detail"))))
                .andExpect(content().string(not(containsString("timed out"))));
    }
}
