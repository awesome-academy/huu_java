package com.huuhv.foodsndrinks.controller.api;

import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.security.JwtUtil;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JWT phải khớp iss/aud; CORS của /api/** chỉ mở cho origin trong allowlist. */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.cors.allowed-origins=https://spa.example.com")
@Transactional // rollback: mọi test class dùng chung MySQL của Testcontainers
class JwtCorsApiTest {

    private static final String USERNAME = "jwtcorsuser";
    private static final String ALLOWED_ORIGIN = "https://spa.example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;

    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        userRepository.save(User.builder()
                .username(USERNAME)
                .email("jwtcorsuser@example.com")
                .password(passwordEncoder.encode("jwt-cors-pass-1"))
                .fullName("JWT CORS")
                .role(Role.ROLE_USER)
                .isActive(true)
                .build());
        // Secret dev là ${random.value}: mỗi lần resolve ra giá trị khác → lấy đúng khoá JwtUtil đang dùng
        signingKey = (SecretKey) ReflectionTestUtils.getField(jwtUtil, "signingKey");
    }

    /** Token ký đúng khoá; test tự chọn iss/aud. */
    private JwtBuilder token() {
        return Jwts.builder()
                .subject(USERNAME)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey, Jwts.SIG.HS256);
    }

    private ResultActions getProducts(String jwt) throws Exception {
        return mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt));
    }

    @Test
    void tokenFromJwtUtil_isAccepted() throws Exception {
        getProducts(jwtUtil.generateToken(USERNAME, "ROLE_USER")).andExpect(status().isOk());
    }

    @Test
    void tokenWithMatchingIssuerAndAudience_isAccepted() throws Exception {
        getProducts(token().issuer("foodsndrinks").audience().add("foodsndrinks-api").and().compact())
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithWrongIssuer_isRejected() throws Exception {
        getProducts(token().issuer("other-system").audience().add("foodsndrinks-api").and().compact())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithWrongAudience_isRejected() throws Exception {
        getProducts(token().issuer("foodsndrinks").audience().add("other-api").and().compact())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutIssuerAndAudience_isRejected() throws Exception {
        getProducts(token().compact()).andExpect(status().isUnauthorized());
    }

    @Test
    void preflight_fromAllowedOrigin_isAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void preflight_fromOtherOrigin_isRejected() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflight_withMethodNotInAllowlist_isRejected() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void webPages_doNotGetCorsHeaders() throws Exception {
        mockMvc.perform(get("/login").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
