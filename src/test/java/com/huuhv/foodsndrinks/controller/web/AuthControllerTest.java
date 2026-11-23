package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.dto.request.RegisterReqDto;
import com.huuhv.foodsndrinks.service.UserService;
import com.huuhv.foodsndrinks.service.security.PasswordPolicy;
import com.huuhv.foodsndrinks.service.security.RegistrationRateLimitService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Đăng ký ({@link AuthController}): trùng username/email/SĐT bị từ chối trước khi tạo user, giới hạn theo IP, CSRF. */
@SecuredWebMvcTest(AuthController.class)
@Import(PasswordPolicy.class) // @ValidPassword trên RegisterReqDto; không có CompromisedPasswordChecker = không gọi mạng
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private RegistrationRateLimitService registrationRateLimitService;

    @BeforeEach
    void allowRegistration() {
        when(registrationRateLimitService.tryAcquire(anyString())).thenReturn(true);
    }

    private static MockHttpServletRequestBuilder registerForm() {
        return registerForm("correct-horse-battery");
    }

    private static MockHttpServletRequestBuilder registerForm(String password) {
        return post("/register")
                .param("fullName", "Alice Nguyen")
                .param("username", "alice")
                .param("email", "alice@example.com")
                .param("phone", "0912345678")
                .param("password", password)
                .param("confirmPassword", password);
    }

    @Test
    void validForm_createsUser_andRedirectsToLogin() throws Exception {
        mockMvc.perform(registerForm().with(csrf()))
                .andExpect(redirectedUrl("/login"));
        verify(userService).registerNewUser(any(RegisterReqDto.class));
    }

    @Test
    void duplicateUsername_isRejected() throws Exception {
        when(userService.existsByUsername("alice")).thenReturn(true);

        mockMvc.perform(registerForm().with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("web/register"))
                .andExpect(model().attributeHasFieldErrors("registerReqDto", "username"));
        verify(userService, never()).registerNewUser(any());
    }

    @Test
    void duplicateEmail_isRejected() throws Exception {
        when(userService.existsByEmail("alice@example.com")).thenReturn(true);

        mockMvc.perform(registerForm().with(csrf()))
                .andExpect(view().name("web/register"))
                .andExpect(model().attributeHasFieldErrors("registerReqDto", "email"));
        verify(userService, never()).registerNewUser(any());
    }

    @Test
    void duplicatePhone_isRejected() throws Exception {
        when(userService.existsByPhone("0912345678")).thenReturn(true);

        mockMvc.perform(registerForm().with(csrf()))
                .andExpect(view().name("web/register"))
                .andExpect(model().attributeHasFieldErrors("registerReqDto", "phone"));
        verify(userService, never()).registerNewUser(any());
    }

    @Test
    void shortPassword_isRejected_beforeDuplicateLookup() throws Exception {
        mockMvc.perform(registerForm("short").with(csrf()))
                .andExpect(view().name("web/register"))
                .andExpect(model().attributeHasFieldErrors("registerReqDto", "password"));
        verify(userService, never()).existsByUsername(anyString());
        verify(userService, never()).registerNewUser(any());
    }

    @Test
    void withoutCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(registerForm())
                .andExpect(status().isForbidden());
        verify(userService, never()).registerNewUser(any());
    }

    @Test
    void tooManyRegistrationsFromIp_areThrottled() throws Exception {
        when(registrationRateLimitService.tryAcquire(anyString())).thenReturn(false);

        mockMvc.perform(registerForm().with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/register?throttled=true"));
        verify(userService, never()).registerNewUser(any());
    }
}
