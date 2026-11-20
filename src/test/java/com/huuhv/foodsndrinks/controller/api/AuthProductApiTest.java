package com.huuhv.foodsndrinks.controller.api;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.logging.RequestIdFilter;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end flow of the JWT API: login → token → call protected product endpoints. */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // every test rolls back — the test user never persists in the dev DB
class AuthProductApiTest {

    private static final String USERNAME = "apitester";
    private static final String PASSWORD = "apitest123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createTestUser() {
        userRepository.save(User.builder()
                .username(USERNAME)
                .email("apitester@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .fullName("API Tester")
                .build());
    }

    private String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String obtainToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // accessToken is the first field of AuthResDto — extract without extra deps
        return response.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void login_withValidCredentials_returnsToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_withBlankFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.username").isNotEmpty())
                .andExpect(jsonPath("$.message.password").isNotEmpty());
    }

    @Test
    void getProducts_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProducts_withValidToken_returnsPagedList() throws Exception {
        String token = obtainToken();
        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    void getProductDetail_notFound_returns404() throws Exception {
        String token = obtainToken();
        mockMvc.perform(get("/api/v1/products/999999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void anyRequest_returnsServerGeneratedRequestId_evenWhenClientSendsOne() throws Exception {
        var uuid = org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}");

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(header().string(RequestIdFilter.HEADER, uuid));
        // id của client chỉ được ghi vào log (cid=...), không được dùng làm requestId
        mockMvc.perform(get("/api/v1/products").header(RequestIdFilter.HEADER, "trace-abc-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, uuid));
    }

    @Test
    void login_successAndFailure_areWrittenToAuditLog() throws Exception {
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            obtainToken();
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(USERNAME, "wrong-password")))
                    .andExpect(status().isUnauthorized());

            assertThat(audit.lines()).containsExactly(
                    "Login succeeded event.action=LOGIN_SUCCESS event.outcome=success auth.method=API"
                            + " username=" + USERNAME + " source.ip=127.0.0.1",
                    "Login failed event.action=LOGIN_FAILURE event.outcome=failure event.reason=BAD_CREDENTIALS"
                            + " auth.method=API username=" + USERNAME + " source.ip=127.0.0.1");
        }
    }
}
