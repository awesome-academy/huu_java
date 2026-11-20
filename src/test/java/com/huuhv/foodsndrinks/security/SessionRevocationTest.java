package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session web đang đăng nhập bị huỷ ngay ở request tiếp theo khi admin khoá tài khoản hoặc đổi role
 * ({@link AccountStatusFilter}), thay vì dùng tiếp tới khi session hết hạn.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // rollback: mọi test class dùng chung MySQL của Testcontainers
class SessionRevocationTest {

    private static final String PASSWORD = "sessionrevoke123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User createUser(String username, Role role) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .fullName("Session Revoke")
                .role(role)
                .isActive(true)
                .build());
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("remember-me", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("remember-me"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void lockedUser_sessionIsRevokedOnNextRequest() throws Exception {
        User user = createUser("revokelocked", Role.ROLE_USER);
        MockHttpSession session = login(user.getUsername());
        mockMvc.perform(get("/profile").session(session)).andExpect(status().isOk());

        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/profile").session(session))
                    .andExpect(redirectedUrl("/login"))
                    // cookie remember-me bị xoá, không tự đăng nhập lại được
                    .andExpect(cookie().maxAge("remember-me", 0));

            assertThat(audit.lines()).containsExactly("Session revoked event.action=SESSION_REVOKED"
                    + " event.outcome=success event.reason=DISABLED username=revokelocked source.ip=127.0.0.1");
        }
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void lockedUser_onPublicPage_continuesAsGuest() throws Exception {
        User user = createUser("revokepublic", Role.ROLE_USER);
        MockHttpSession session = login(user.getUsername());

        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void demotedAdmin_losesAdminAccessImmediately() throws Exception {
        User admin = createUser("revokeadmin", Role.ROLE_ADMIN);
        MockHttpSession session = login(admin.getUsername());
        mockMvc.perform(get("/admin").session(session)).andExpect(status().isOk());

        admin.setRole(Role.ROLE_USER);
        userRepository.saveAndFlush(admin);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/admin").session(session)).andExpect(redirectedUrl("/login"));

            assertThat(audit.lines()).singleElement().asString()
                    .contains("event.action=SESSION_REVOKED", "event.reason=ROLE_CHANGED", "username=revokeadmin");
        }
    }

    @Test
    void activeUser_keepsSession() throws Exception {
        User user = createUser("revokeactive", Role.ROLE_USER);
        MockHttpSession session = login(user.getUsername());

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/profile").session(session)).andExpect(status().isOk());
            mockMvc.perform(get("/profile").session(session)).andExpect(status().isOk());
            assertThat(audit.lines()).isEmpty();
        }
        assertThat(session.isInvalid()).isFalse();
    }
}
