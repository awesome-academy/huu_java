package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.dto.request.UserEditReqDto;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.UserService;
import com.huuhv.foodsndrinks.service.audit.AuditLogTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session web đang đăng nhập bị huỷ ngay ở request tiếp theo khi admin khoá tài khoản, đổi role, hoặc khi mật khẩu được
 * đổi/đặt lại sau lúc session đó đăng nhập ({@link AccountStatusFilter}), thay vì dùng tiếp tới khi session hết hạn.
 * <p>
 * Session nằm trong kho Spring Session JDBC (filter của nó chạy cả trong MockMvc): "trình duyệt" của test là COOKIE
 * session mà response trả về, mang theo từng request — {@code MockHttpSession} không còn tác dụng vì Spring Session bỏ
 * qua session của container. Session bị huỷ = dòng của nó bị xoá khỏi kho → cùng cookie đó không còn đăng nhập được ở
 * BẤT KỲ instance nào (kịch bản nhiều instance trên Tomcat thật: {@code MultiInstanceRealtimeIntegrationTest}).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // rollback: mọi test class dùng chung MySQL của Testcontainers
class SessionRevocationTest {

    private static final String PASSWORD = "sessionrevoke123";
    private static final String NEW_PASSWORD = "sessionrevoke456-new";
    private static final String REMEMBER_ME = "remember-me";
    /**
     * Tên cookie session: {@code JSESSIONID} khi chạy trên web server thật ({@code server.servlet.session.cookie.name},
     * xem {@code SessionCookieTest}); trong MockMvc không có web server nên Spring Session dùng tên mặc định của nó.
     */
    private static final List<String> SESSION_COOKIE_NAMES = List.of("JSESSIONID", "SESSION");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserService userService;

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

    /** Đăng nhập form (có tick "ghi nhớ đăng nhập"); trả cookie session của "trình duyệt" đó. */
    private Cookie login(String username) throws Exception {
        return sessionOf(loginResult(username, PASSWORD));
    }

    private MvcResult loginResult(String username, String password) throws Exception {
        return mockMvc.perform(post("/login")
                        .param("username", username)
                        .param("password", password)
                        .param("remember-me", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists(REMEMBER_ME))
                .andReturn();
    }

    /** Cookie session mà response vừa cấp (session mới hoặc session vừa đổi id). */
    private static Cookie sessionOf(MvcResult result) {
        Cookie[] cookies = result.getResponse().getCookies();
        return Arrays.stream(cookies)
                .filter(candidate -> SESSION_COOKIE_NAMES.contains(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Response did not set a session cookie, only: "
                        + Arrays.stream(cookies).map(Cookie::getName).toList()));
    }

    private static String randomPhone() {
        return "09" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
    }

    /** {@code true} nếu cookie session còn đăng nhập được (trang cần đăng nhập trả 200 chứ không về /login). */
    private boolean isLoggedIn(Cookie session) throws Exception {
        return mockMvc.perform(get("/profile").cookie(session)).andReturn().getResponse().getStatus() == 200;
    }

    /** User tự đổi mật khẩu ở trang hồ sơ, trên session {@code session} (kèm cookie remember-me nếu có). */
    private MvcResult changeOwnPassword(User user, Cookie session, Cookie rememberMe) throws Exception {
        MockHttpServletRequestBuilder request = post("/profile/edit").with(csrf())
                .param("fullName", user.getFullName())
                .param("email", user.getEmail())
                .param("phone", randomPhone())
                .param("currentPassword", PASSWORD)
                .param("newPassword", NEW_PASSWORD)
                .param("confirmNewPassword", NEW_PASSWORD);
        if (rememberMe != null) {
            request.cookie(session, rememberMe);
        } else {
            request.cookie(session);
        }
        return mockMvc.perform(request).andExpect(redirectedUrl("/profile")).andReturn();
    }

    @Test
    void lockedUser_sessionIsRevokedOnNextRequest() throws Exception {
        User user = createUser("revokelocked", Role.ROLE_USER);
        Cookie session = login(user.getUsername());
        mockMvc.perform(get("/profile").cookie(session)).andExpect(status().isOk());

        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/profile").cookie(session))
                    .andExpect(redirectedUrl("/login"))
                    // cookie remember-me bị xoá, không tự đăng nhập lại được
                    .andExpect(cookie().maxAge(REMEMBER_ME, 0));

            assertThat(audit.lines()).containsExactly("Session revoked event.action=SESSION_REVOKED"
                    + " event.outcome=success event.reason=DISABLED username=revokelocked source.ip=127.0.0.1");
        }
        // Session đã bị xoá khỏi kho: mở khoá lại thì cookie cũ cũng không đăng nhập lại được
        user.setIsActive(true);
        userRepository.saveAndFlush(user);
        assertThat(isLoggedIn(session)).isFalse();
    }

    @Test
    void lockedUser_onPublicPage_continuesAsGuest() throws Exception {
        User user = createUser("revokepublic", Role.ROLE_USER);
        Cookie session = login(user.getUsername());

        user.setIsActive(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/").cookie(session)).andExpect(status().isOk());

        user.setIsActive(true);
        userRepository.saveAndFlush(user);
        assertThat(isLoggedIn(session)).isFalse();
    }

    @Test
    void demotedAdmin_losesAdminAccessImmediately() throws Exception {
        User admin = createUser("revokeadmin", Role.ROLE_ADMIN);
        Cookie session = login(admin.getUsername());
        mockMvc.perform(get("/admin").cookie(session)).andExpect(status().isOk());

        admin.setRole(Role.ROLE_USER);
        userRepository.saveAndFlush(admin);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/admin").cookie(session)).andExpect(redirectedUrl("/login"));

            assertThat(audit.lines()).singleElement().asString()
                    .contains("event.action=SESSION_REVOKED", "event.reason=ROLE_CHANGED", "username=revokeadmin");
        }
    }

    @Test
    void activeUser_keepsSession() throws Exception {
        User user = createUser("revokeactive", Role.ROLE_USER);
        Cookie session = login(user.getUsername());

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/profile").cookie(session)).andExpect(status().isOk());
            mockMvc.perform(get("/profile").cookie(session)).andExpect(status().isOk());
            assertThat(audit.lines()).isEmpty();
        }
        assertThat(isLoggedIn(session)).isTrue();
    }

    // -------------------------------------------------------
    // Đổi / đặt lại mật khẩu (issue #37)
    // -------------------------------------------------------

    @Test
    void ownPasswordChange_keepsTheSessionThatChangedIt_andRevokesEveryOtherSession() throws Exception {
        User user = createUser("pwdself", Role.ROLE_USER);
        Cookie changer = login(user.getUsername());
        Cookie otherBrowser = login(user.getUsername());

        Cookie afterChange = sessionOf(changeOwnPassword(user, changer, null));

        // Session vừa đổi mật khẩu: dùng tiếp được dưới id MỚI; id cũ (có thể đã lộ) không còn giá trị
        assertThat(afterChange.getValue()).isNotEqualTo(changer.getValue());
        mockMvc.perform(get("/profile").cookie(afterChange)).andExpect(status().isOk());
        mockMvc.perform(get("/profile").cookie(afterChange)).andExpect(status().isOk());
        assertThat(isLoggedIn(changer)).isFalse();

        // Session đăng nhập bằng mật khẩu cũ ở trình duyệt khác: bị huỷ ở request kế tiếp của nó
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/profile").cookie(otherBrowser))
                    .andExpect(redirectedUrl("/login"))
                    .andExpect(cookie().maxAge(REMEMBER_ME, 0));

            assertThat(audit.lines()).containsExactly("Session revoked event.action=SESSION_REVOKED"
                    + " event.outcome=success event.reason=PASSWORD_CHANGED username=pwdself source.ip=127.0.0.1");
        }
        assertThat(isLoggedIn(otherBrowser)).isFalse();
        assertThat(isLoggedIn(afterChange)).isTrue();
    }

    @Test
    void ownPasswordChange_reissuesRememberMeCookie_andTheOldOneNoLongerLogsIn() throws Exception {
        User user = createUser("pwdremember", Role.ROLE_USER);
        MvcResult login = loginResult(user.getUsername(), PASSWORD);
        Cookie oldCookie = login.getResponse().getCookie(REMEMBER_ME);

        Cookie newCookie = changeOwnPassword(user, sessionOf(login), oldCookie).getResponse().getCookie(REMEMBER_ME);

        // Cookie cũ ký bằng hash mật khẩu cũ → vô hiệu; trình duyệt đang ghi nhớ đăng nhập nhận cookie mới
        assertThat(newCookie).isNotNull();
        assertThat(newCookie.getValue()).isNotBlank().isNotEqualTo(oldCookie.getValue());
        assertThat(newCookie.getMaxAge()).isPositive();
        mockMvc.perform(get("/profile").cookie(oldCookie)).andExpect(redirectedUrl("/login"));

        // Session mới tạo bằng cookie mới là một lần xác thực SAU khi đổi mật khẩu → không bị huỷ
        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            MvcResult autoLogin = mockMvc.perform(get("/profile").cookie(newCookie))
                    .andExpect(status().isOk()).andReturn();
            mockMvc.perform(get("/profile").cookie(sessionOf(autoLogin))).andExpect(status().isOk());

            assertThat(audit.lines()).noneMatch(line -> line.contains("SESSION_REVOKED"));
        }
    }

    @Test
    void ownPasswordChange_withoutRememberMeCookie_doesNotStartRememberingTheBrowser() throws Exception {
        User user = createUser("pwdnoremember", Role.ROLE_USER);
        Cookie session = login(user.getUsername());

        MvcResult changed = changeOwnPassword(user, session, null);

        assertThat(changed.getResponse().getCookie(REMEMBER_ME)).isNull();
    }

    @Test
    void adminPasswordReset_revokesEverySessionOfThatUser_andLoginWithTheNewPasswordIsKept() throws Exception {
        User user = createUser("pwdreset", Role.ROLE_USER);
        Cookie first = login(user.getUsername());
        Cookie second = login(user.getUsername());
        mockMvc.perform(get("/profile").cookie(first)).andExpect(status().isOk());

        UserEditReqDto reset = userService.getUserForEdit(user.getId());
        reset.setPhone(randomPhone());
        reset.setNewPassword(NEW_PASSWORD);
        userService.updateUser(user.getId(), reset);

        try (AuditLogTestSupport audit = new AuditLogTestSupport()) {
            mockMvc.perform(get("/profile").cookie(first)).andExpect(redirectedUrl("/login"));
            mockMvc.perform(get("/").cookie(second)).andExpect(status().isOk());   // trang public: tiếp tục như khách

            assertThat(audit.lines()).filteredOn(line -> line.contains("SESSION_REVOKED")).hasSize(2)
                    .allSatisfy(line -> assertThat(line).contains("event.reason=PASSWORD_CHANGED", "username=pwdreset"));
        }
        assertThat(isLoggedIn(first)).isFalse();
        assertThat(isLoggedIn(second)).isFalse();

        // Đăng nhập lại bằng mật khẩu mới: session mới (xác thực sau mốc đổi) không bị huỷ
        Cookie fresh = sessionOf(loginResult(user.getUsername(), NEW_PASSWORD));
        mockMvc.perform(get("/profile").cookie(fresh)).andExpect(status().isOk());
        mockMvc.perform(get("/profile").cookie(fresh)).andExpect(status().isOk());
    }

    @Test
    void profileUpdateWithoutPasswordChange_keepsOtherSessions() throws Exception {
        User user = createUser("pwdunchanged", Role.ROLE_USER);
        Cookie editor = login(user.getUsername());
        Cookie otherBrowser = login(user.getUsername());

        mockMvc.perform(post("/profile/edit").cookie(editor).with(csrf())
                        .param("fullName", "Renamed User")
                        .param("email", user.getEmail())
                        .param("phone", randomPhone()))
                .andExpect(redirectedUrl("/profile"));

        assertThat(isLoggedIn(otherBrowser)).isTrue();
        assertThat(isLoggedIn(editor)).isTrue();
    }
}
