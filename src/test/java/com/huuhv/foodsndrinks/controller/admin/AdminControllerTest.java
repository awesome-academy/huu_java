package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.exception.DashboardUnavailableException;
import com.huuhv.foodsndrinks.service.DashboardChartCache;
import com.huuhv.foodsndrinks.service.DashboardService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
