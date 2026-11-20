package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chính sách mật khẩu 8–64 ký tự ở form đăng ký (validation phía server, không tin maxlength của trình duyệt). */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // rollback user được đăng ký thành công
class PasswordPolicyFormTest {

    @Autowired private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder register(String password) {
        return post("/register").with(csrf())
                .param("fullName", "Policy Tester")
                .param("username", "policytester")
                .param("email", "policytester@example.com")
                .param("phone", "0912345671")
                .param("password", password)
                .param("confirmPassword", password);
    }

    @Test
    void register_withSevenCharPassword_isRejectedWithFieldError() throws Exception {
        mockMvc.perform(register("1234567"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrorCode("registerReqDto", "password", "ValidPassword"));
    }

    @Test
    void register_with64CharPassword_isAccepted() throws Exception {
        mockMvc.perform(register("p".repeat(64)))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void register_withoutCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/register").param("password", "whatever-123"))
                .andExpect(status().isForbidden());
    }
}
