package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/** Audit log của form login đi qua security chain web thật: mỗi lần đăng nhập ghi đúng 1 dòng. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // rollback user test, không để lại dữ liệu trong DB dev
class FormLoginAuditTest {

    private static final String USERNAME = "formaudituser";
    private static final String PASSWORD = "formaudit123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createTestUser() {
        userRepository.save(User.builder()
                .username(USERNAME)
                .email("formaudituser@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .fullName("Form Audit")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
    }

    @Test
    void formLogin_successAndFailure_areAuditedOnce() throws Exception {
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(formLogin("/login").user(USERNAME).password(PASSWORD))
                    .andExpect(redirectedUrl("/"));
            mockMvc.perform(formLogin("/login").user(USERNAME).password("wrong-password"))
                    .andExpect(redirectedUrl("/login?error=true"));

            assertThat(audit.messages()).containsExactly(
                    "event=LOGIN_SUCCESS method=FORM user=" + USERNAME + " ip=127.0.0.1",
                    "event=LOGIN_FAILURE method=FORM user=" + USERNAME + " reason=BAD_CREDENTIALS ip=127.0.0.1");
        }
    }
}
