package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chống brute-force qua security chain thật (mặc định 5 lần sai/15 phút). Bộ đếm nằm trong bộ nhớ và dùng chung giữa
 * các test cùng Spring context (không rollback theo transaction) → mỗi test dùng tên đăng nhập riêng.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // rollback: mọi test class dùng chung MySQL của Testcontainers
class LoginThrottlingTest {

    private static final String PASSWORD = "throttle-pass-123";
    private static final int MAX_FAILURES = 5;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private void createUser(String username) {
        userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .fullName("Throttle Test")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
    }

    private ResultActions apiLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void formLogin_lockedAfterMaxFailures_evenWithCorrectPassword() throws Exception {
        createUser("throttleform");
        for (int i = 0; i < MAX_FAILURES; i++) {
            mockMvc.perform(formLogin("/login").user("throttleform").password("wrong-password"))
                    .andExpect(redirectedUrl("/login?error=true"));
        }

        mockMvc.perform(formLogin("/login").user("throttleform").password(PASSWORD))
                .andExpect(redirectedUrl("/login?locked=true"));
    }

    @Test
    void apiLogin_lockedAfterMaxFailures_returns429WithRetryAfter_andAudits() throws Exception {
        createUser("throttleapi");
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            for (int i = 0; i < MAX_FAILURES; i++) {
                apiLogin("throttleapi", "wrong-password").andExpect(status().isUnauthorized());
            }

            apiLogin("throttleapi", PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("\\d+")))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.message").value("Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau."));

            assertThat(audit.lines())
                    .anySatisfy(line -> assertThat(line).startsWith(
                            "Login locked out event.action=LOGIN_LOCKOUT event.outcome=failure username=throttleapi"
                                    + " auth.failures=5 auth.lock.seconds=900"))
                    .last().asString()
                    .contains("event.reason=LOGIN_THROTTLED auth.method=API username=throttleapi ");
        }
    }

    @Test
    void unknownUsername_isLockedTheSameWay_soAccountsCannotBeEnumerated() throws Exception {
        for (int i = 0; i < MAX_FAILURES; i++) {
            apiLogin("throttle-nobody", "wrong-password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Tên đăng nhập, email hoặc mật khẩu không chính xác."));
        }

        apiLogin("throttle-nobody", "wrong-password").andExpect(status().isTooManyRequests());
    }

    @Test
    void accentedOrFullwidthVariantsOfTheName_shareOneCounter() throws Exception {
        createUser("throttleuni");
        // MySQL (utf8mb4_0900_ai_ci) coi các biến thể này là cùng một username → phải đếm chung
        for (String variant : new String[]{"thróttleuni", "THROTTLEUNI", "ｔｈｒｏｔｔｌｅｕｎｉ", "throttleüni", "throttleuni"}) {
            apiLogin(variant, "wrong-password").andExpect(status().isUnauthorized());
        }

        apiLogin("throttleuni", PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLogin_resetsCounter() throws Exception {
        createUser("throttlereset");
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            apiLogin("throttlereset", "wrong-password").andExpect(status().isUnauthorized());
        }
        apiLogin("throttlereset", PASSWORD).andExpect(status().isOk());

        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            apiLogin("throttlereset", "wrong-password").andExpect(status().isUnauthorized());
        }
        apiLogin("throttlereset", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void loginByEmail_successResetsTheEmailCounter() throws Exception {
        createUser("throttleemail");
        String email = "throttleemail@example.com";
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            apiLogin(email, "wrong-password").andExpect(status().isUnauthorized());
        }
        apiLogin(email, PASSWORD).andExpect(status().isOk());

        apiLogin(email, "wrong-password").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordOnDisabledAccount_isPlainBadCredentials_notDisabled() throws Exception {
        createUser("throttledisabled");
        User user = userRepository.findByUsernameOrEmail("throttledisabled", "throttledisabled").orElseThrow();
        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        apiLogin("throttledisabled", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Tên đăng nhập, email hoặc mật khẩu không chính xác."));
        apiLogin("throttledisabled", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Tài khoản đã bị vô hiệu hoá."));
    }

    @Test
    void veryLongPassword_isUnauthorized_notServerError() throws Exception {
        createUser("throttlelong");

        apiLogin("throttlelong", "x".repeat(200)).andExpect(status().isUnauthorized());
    }

    @Test
    void formLogin_withoutCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/login").param("username", "throttlecsrf").param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }
}
