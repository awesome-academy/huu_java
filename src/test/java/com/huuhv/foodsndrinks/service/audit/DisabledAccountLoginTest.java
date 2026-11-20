package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.TestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tài khoản bị khoá ({@code is_active = false}) đăng nhập thật qua security chain: audit phải ghi
 * {@code event.reason=DISABLED} (không phải lỗi hệ thống), API trả 401 (không phải 500), và JWT cấp trước khi khoá
 * không còn dùng được.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // rollback user test, không để lại dữ liệu trong DB dev
class DisabledAccountLoginTest {

    private static final String USERNAME = "lockedaudituser";
    private static final String PASSWORD = "lockedaudit123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User user;

    @BeforeEach
    void createTestUser() {
        user = userRepository.save(User.builder()
                .username(USERNAME)
                .email("lockedaudituser@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .fullName("Locked Audit")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
    }

    private void lockUser() {
        user.setIsActive(false);
        userRepository.saveAndFlush(user);
    }

    private static String loginBody() {
        return "{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    @Test
    void formLogin_ofDisabledAccount_isAuditedAsDisabled() throws Exception {
        lockUser();

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(formLogin("/login").user(USERNAME).password(PASSWORD))
                    .andExpect(redirectedUrl("/login?error=true"));

            assertThat(audit.lines()).containsExactly(
                    "Login failed event.action=LOGIN_FAILURE event.outcome=failure event.reason=DISABLED"
                            + " auth.method=FORM username=" + USERNAME + " source.ip=127.0.0.1");
        }
    }

    @Test
    void apiLogin_ofDisabledAccount_returns401_andIsAuditedAsDisabled() throws Exception {
        lockUser();

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody()))
                    .andExpect(status().isUnauthorized());

            assertThat(audit.lines()).singleElement().asString()
                    .contains("event.reason=DISABLED auth.method=API username=" + USERNAME + " ");
        }
    }

    @Test
    void jwtIssuedBeforeLock_isRejected() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        lockUser();

        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
