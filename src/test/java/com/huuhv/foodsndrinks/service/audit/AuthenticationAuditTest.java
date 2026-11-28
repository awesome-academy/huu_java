package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.OAuthEmailConflictException;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.security.AuditingRememberMeServices;
import com.huuhv.foodsndrinks.security.DisabledOAuth2AccountException;
import com.huuhv.foodsndrinks.security.OAuth2EmailConflictException;
import com.huuhv.foodsndrinks.security.OAuth2LoginFailureHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureDisabledEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link AuditLogService}, {@link AuthenticationAuditListener}, {@link OAuth2LoginFailureHandler} và
 * {@link AuditingRememberMeServices} — không khởi động Spring, không cần DB.
 */
class AuthenticationAuditTest {

    private static final String EXISTING_USER = "alice";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogService auditLogService = new AuditLogService(userRepository);
    private final AuthenticationAuditListener listener = new AuthenticationAuditListener(auditLogService);
    private AuditLogTestSupport audit;

    @BeforeEach
    void setUp() {
        when(userRepository.existsByUsernameOrEmail(anyString(), anyString()))
                .thenAnswer(inv -> EXISTING_USER.equals(inv.getArgument(0)));
        audit = new AuditLogTestSupport();
    }

    @AfterEach
    void tearDown() {
        audit.close();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest currentRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    // -------------------------------------------------------
    // Login
    // -------------------------------------------------------

    @Test
    void formLoginSuccess_isAudited() {
        currentRequest("/login");

        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated(EXISTING_USER, null, List.of())));

        assertThat(audit.lines()).containsExactly("Login succeeded event.action=LOGIN_SUCCESS event.outcome=success"
                + " auth.method=FORM username=alice source.ip=10.0.0.7");
    }

    @Test
    void apiLoginFailure_ofExistingAccount_logsUsername_butNeverPassword() {
        currentRequest("/api/v1/auth/login");

        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated(EXISTING_USER, "S3cret-password"),
                new BadCredentialsException("x")));

        assertThat(audit.lines()).singleElement().satisfies(line -> {
            assertThat(line).isEqualTo("Login failed event.action=LOGIN_FAILURE event.outcome=failure"
                    + " event.reason=BAD_CREDENTIALS auth.method=API username=alice source.ip=10.0.0.7");
            assertThat(line).doesNotContain("S3cret-password");
        });
    }

    @Test
    void soapHttpBasicLogin_isAuditedAsSoap_onTheServicePathAndBelowIt_butNotOnTheWebSocketPath() {
        // POST /ws là đường dẫn thật của SOAP (không có "/" cuối) — so tiền tố "/ws/" sẽ bỏ sót nó
        for (String soapPath : List.of("/ws", "/ws/", "/ws/anything")) {
            currentRequest(soapPath);
            listener.onSuccess(new AuthenticationSuccessEvent(
                    UsernamePasswordAuthenticationToken.authenticated(EXISTING_USER, null, List.of())));
        }
        // /ws-stomp là handshake WebSocket của chain web (đăng nhập form), không phải SOAP
        currentRequest("/ws-stomp");
        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated(EXISTING_USER, null, List.of())));

        assertThat(audit.lines()).extracting(line -> line.replaceAll(".*auth\\.method=(\\S+).*", "$1"))
                .containsExactly("SOAP", "SOAP", "SOAP", "FORM");
    }

    @Test
    void loginFailure_ofUnknownAccount_doesNotLogTypedUsername() {
        currentRequest("/login");

        // Người dùng gõ nhầm mật khẩu vào ô username
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("MyS3cretPass!", "whatever"),
                new BadCredentialsException("x")));

        assertThat(audit.lines()).singleElement().satisfies(line -> {
            assertThat(line).contains(" username=- ");
            assertThat(line).doesNotContain("MyS3cretPass!");
        });
    }

    @Test
    void disabledAccount_isAuditedWithReason() {
        currentRequest("/login");

        listener.onFailure(new AuthenticationFailureDisabledEvent(
                UsernamePasswordAuthenticationToken.unauthenticated(EXISTING_USER, "pw"),
                new DisabledException("locked")));

        assertThat(audit.lines()).singleElement().asString()
                .contains("event.reason=DISABLED auth.method=FORM username=alice");
    }

    @Test
    void rememberMeLoginSuccess_isAuditedAsRememberMe() {
        currentRequest("/profile");

        listener.onSuccess(new AuthenticationSuccessEvent(new RememberMeAuthenticationToken(
                "key", "carol", AuthorityUtils.createAuthorityList("ROLE_USER"))));

        assertThat(audit.lines()).singleElement().asString().contains("auth.method=REMEMBER_ME username=carol");
    }

    @Test
    void valuesWithSpecialCharacters_areQuoted() {
        currentRequest("/login");

        auditLogService.loginSuccess("FORM", "a=b \"x\"@x.com");

        assertThat(audit.lines()).singleElement().asString().contains(" username=\"a=b \\\"x\\\"@x.com\" ");
    }

    @Test
    void longEmailsWithSamePrefix_stayDistinguishable() {
        currentRequest("/login");
        String prefix = "a".repeat(70);

        auditLogService.loginSuccess("FORM", prefix + "-one@example.com");
        auditLogService.loginSuccess("FORM", prefix + "-two@example.com");

        assertThat(audit.lines()).satisfiesExactly(
                first -> assertThat(first).contains(" username=" + prefix + "-one@example.com "),
                second -> assertThat(second).contains(" username=" + prefix + "-two@example.com "));
    }

    @Test
    void valueLongerThanEmailColumn_isTruncated() {
        currentRequest("/login");

        auditLogService.loginSuccess("FORM", "x".repeat(AuditLogService.MAX_FIELD_LENGTH + 50));

        assertThat(audit.lines()).singleElement().asString()
                .contains(" username=" + "x".repeat(AuditLogService.MAX_FIELD_LENGTH) + "... ");
    }

    private OAuth2LoginFailureHandler oauth2FailureHandler() {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("client-id").clientSecret("client-secret").build();
        return new OAuth2LoginFailureHandler(auditLogService, new InMemoryClientRegistrationRepository(google));
    }

    @Test
    void oauth2Failure_isAudited_andRedirectsToLoginError() throws Exception {
        MockHttpServletRequest request = currentRequest("/login/oauth2/code/google");
        var response = new MockHttpServletResponse();

        oauth2FailureHandler().onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "user denied", null)));

        assertThat(audit.lines()).containsExactly("Login failed event.action=LOGIN_FAILURE event.outcome=failure"
                + " event.reason=access_denied auth.method=OAUTH2:google username=- source.ip=10.0.0.7");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=true");
    }

    @Test
    void oauth2Login_ofDisabledAccount_isAuditedLikeFormLogin_andRedirectsToLoginError() throws Exception {
        MockHttpServletRequest request = currentRequest("/login/oauth2/code/google");
        var response = new MockHttpServletResponse();

        oauth2FailureHandler().onAuthenticationFailure(request, response,
                new DisabledOAuth2AccountException(EXISTING_USER));

        assertThat(audit.lines()).containsExactly("Login failed event.action=LOGIN_FAILURE event.outcome=failure"
                + " event.reason=DISABLED auth.method=OAUTH2:google username=alice source.ip=10.0.0.7");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=true");
    }

    @Test
    void oauth2Login_withEmailOfUnlinkableAccount_isAudited_andRedirectsToEmailConflictMessage() throws Exception {
        MockHttpServletRequest request = currentRequest("/login/oauth2/code/google");
        var response = new MockHttpServletResponse();

        oauth2FailureHandler().onAuthenticationFailure(request, response,
                new OAuth2EmailConflictException(new OAuthEmailConflictException(EXISTING_USER)));

        assertThat(audit.lines()).containsExactly("Login failed event.action=LOGIN_FAILURE event.outcome=failure"
                + " event.reason=EMAIL_CONFLICT auth.method=OAUTH2:google username=alice source.ip=10.0.0.7");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauthEmailExists=true");
    }

    @Test
    void oauth2Failure_withUnregisteredProviderAndUnknownErrorCode_doesNotLogAttackerValues() throws Exception {
        MockHttpServletRequest request = currentRequest("/login/oauth2/code/FORM_ADMIN_BYPASS");

        oauth2FailureHandler().onAuthenticationFailure(request, new MockHttpServletResponse(),
                new OAuth2AuthenticationException(new OAuth2Error("totally_fake_reason")));

        assertThat(audit.lines()).singleElement().asString()
                .contains("event.reason=OTHER auth.method=OAUTH2:unknown ")
                .doesNotContain("FORM_ADMIN_BYPASS")
                .doesNotContain("totally_fake_reason");
    }

    @Test
    void oauth2Failure_withEmptyRegistrationId_isLoggedAsUnknown() throws Exception {
        MockHttpServletRequest request = currentRequest("/login/oauth2/code/");

        oauth2FailureHandler().onAuthenticationFailure(request, new MockHttpServletResponse(),
                new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found")));

        assertThat(audit.lines()).singleElement().asString()
                .contains("event.reason=authorization_request_not_found auth.method=OAUTH2:unknown ");
    }

    // -------------------------------------------------------
    // Remember-me cookie bị từ chối (không sinh event)
    // -------------------------------------------------------

    private AuditingRememberMeServices rememberMeServices() {
        return rememberMeServices(false);
    }

    private AuditingRememberMeServices rememberMeServices(boolean disabled) {
        UserDetailsService userDetailsService = username -> User.withUsername(username)
                .password("{noop}pw").authorities("ROLE_USER").disabled(disabled).build();
        return new AuditingRememberMeServices("test-key", userDetailsService, auditLogService);
    }

    /** Cookie remember-me có chữ ký hợp lệ, do chính TokenBasedRememberMeServices sinh ra khi đăng nhập. */
    private Cookie validRememberMeCookie(String username) {
        AuditingRememberMeServices services = rememberMeServices();
        services.setAlwaysRemember(true);
        var response = new MockHttpServletResponse();
        services.loginSuccess(new MockHttpServletRequest(), response,
                UsernamePasswordAuthenticationToken.authenticated(
                        User.withUsername(username).password("{noop}pw").authorities("ROLE_USER").build(),
                        null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        return response.getCookie("remember-me");
    }

    @Test
    void validRememberMeCookie_ofDisabledAccount_isAuditedAsDisabled() {
        Cookie cookie = validRememberMeCookie(EXISTING_USER);
        MockHttpServletRequest request = currentRequest("/profile");
        request.setCookies(cookie);

        assertThat(rememberMeServices(true).autoLogin(request, new MockHttpServletResponse())).isNull();

        assertThat(audit.lines()).containsExactly("Login failed event.action=LOGIN_FAILURE event.outcome=failure"
                + " event.reason=DISABLED auth.method=REMEMBER_ME username=alice source.ip=10.0.0.7");
    }

    @Test
    void validRememberMeCookie_ofActiveAccount_isNotAuditedAsFailure() {
        Cookie cookie = validRememberMeCookie(EXISTING_USER);
        MockHttpServletRequest request = currentRequest("/profile");
        request.setCookies(cookie);

        assertThat(rememberMeServices().autoLogin(request, new MockHttpServletResponse())).isNotNull();

        assertThat(audit.lines()).isEmpty();
    }

    @Test
    void malformedRememberMeCookie_isAudited() {
        MockHttpServletRequest request = currentRequest("/profile");
        request.setCookies(new Cookie("remember-me", "not*base64!"));

        assertThat(rememberMeServices().autoLogin(request, new MockHttpServletResponse())).isNull();

        assertThat(audit.lines()).containsExactly("Login failed event.action=LOGIN_FAILURE event.outcome=failure"
                + " event.reason=INVALID_COOKIE auth.method=REMEMBER_ME username=- source.ip=10.0.0.7");
    }

    @Test
    void forgedRememberMeCookie_isAudited() {
        MockHttpServletRequest request = currentRequest("/profile");
        String forged = Base64.getEncoder().encodeToString(
                "alice:9999999999999:SHA256:deadbeef".getBytes(StandardCharsets.UTF_8));
        request.setCookies(new Cookie("remember-me", forged));

        assertThat(rememberMeServices().autoLogin(request, new MockHttpServletResponse())).isNull();

        assertThat(audit.lines()).singleElement().asString()
                .contains("event.reason=INVALID_COOKIE auth.method=REMEMBER_ME username=alice");
    }

    // -------------------------------------------------------
    // Thay đổi tài khoản / đơn hàng
    // -------------------------------------------------------

    @Test
    void adminChanges_recordActorFromSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));

        auditLogService.userRoleChanged(5L, "dave", Role.ROLE_USER, Role.ROLE_ADMIN);
        auditLogService.userActiveChanged(5L, "dave", false);
        auditLogService.userPasswordReset(5L, "dave");
        auditLogService.userEmailChanged(5L, "dave");

        assertThat(audit.lines()).containsExactly(
                "User role changed event.action=USER_ROLE_CHANGED event.outcome=success actor=admin target.id=5"
                        + " target.username=dave target.role.from=ROLE_USER target.role.to=ROLE_ADMIN source.ip=-",
                "User locked event.action=USER_LOCKED event.outcome=success actor=admin target.id=5"
                        + " target.username=dave source.ip=-",
                "User password reset event.action=USER_PASSWORD_RESET event.outcome=success actor=admin target.id=5"
                        + " target.username=dave source.ip=-",
                "User email changed event.action=USER_EMAIL_CHANGED event.outcome=success actor=admin target.id=5"
                        + " target.username=dave source.ip=-");
    }

    @Test
    void selfPasswordChanges_areAudited() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(EXISTING_USER, null, List.of()));

        auditLogService.passwordChangeFailed(3L, EXISTING_USER, "WRONG_CURRENT_PASSWORD");
        auditLogService.passwordChanged(3L, EXISTING_USER);

        assertThat(audit.lines()).containsExactly(
                "Password change failed event.action=PASSWORD_CHANGE_FAILED event.outcome=failure actor=alice"
                        + " target.id=3 target.username=alice event.reason=WRONG_CURRENT_PASSWORD source.ip=-",
                "Password changed event.action=PASSWORD_CHANGED event.outcome=success actor=alice"
                        + " target.id=3 target.username=alice source.ip=-");
    }

    @Test
    void orderStatusChange_isLoggedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditLogService.orderStatusChanged(9L, OrderStatus.PENDING, OrderStatus.PROCESSING);
            assertThat(audit.lines()).isEmpty();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(audit.lines()).containsExactly("Order status changed event.action=ORDER_STATUS_CHANGED"
                + " event.outcome=success actor=system order.id=9 order.status.from=PENDING"
                + " order.status.to=PROCESSING source.ip=-");
    }

    @Test
    void reasonCode_isDerivedFromClassName() {
        var event = new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("x", "y"), new BadCredentialsException("x"));
        assertThat(AuthenticationAuditListener.reason(event)).isEqualTo("BAD_CREDENTIALS");
        assertThat(AuditLogService.toReasonCode(new InvalidCookieException("x"))).isEqualTo("INVALID_COOKIE");
        assertThat(AuditLogService.toReasonCode(new DisabledException("x"))).isEqualTo("DISABLED");
    }
}
