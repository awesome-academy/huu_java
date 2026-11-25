package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint JSON thay cho dữ liệu inline của biểu đồ dashboard (CSP).
 * <p>
 * User của {@code @WithMockUser} phải có thật trong DB với đúng role: {@code AccountStatusFilter} huỷ session của
 * tài khoản không tồn tại hoặc khác role.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // rollback: mọi test class dùng chung MySQL của Testcontainers
class DashboardChartsTest {

    private static final String URL = "/admin/dashboard/charts";
    private static final String ADMIN = "chartstestadmin";
    private static final String USER = "chartstestuser";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void createTestUsers() {
        createUser(ADMIN, Role.ROLE_ADMIN);
        createUser(USER, Role.ROLE_USER);
    }

    private void createUser(String username, Role role) {
        userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .fullName("Charts Test")
                .role(role)
                .isActive(true)
                .build());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void admin_getsChartData() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.monthLabels", hasSize(6)))
                .andExpect(jsonPath("$.monthRevenues", hasSize(6)))
                .andExpect(jsonPath("$.statusLabels", hasSize(4)))
                .andExpect(jsonPath("$.statusCounts", hasSize(4)));
    }

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(username = USER, roles = "USER")
    void user_isForbidden() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void dashboardPage_loadsChartScriptFromFile_notInline() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-charts-url=\"/admin/dashboard/charts\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/admin/dashboard.js")));
    }
}
