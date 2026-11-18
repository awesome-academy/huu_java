package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.security.OAuth2LoginFailureHandler;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test cho {@link AuditLogService}, {@link AuthenticationAuditListener} và {@link OAuth2LoginFailureHandler}. */
class AuthenticationAuditTest {

    private final AuditLogService auditLogService = new AuditLogService();
    private final AuthenticationAuditListener listener = new AuthenticationAuditListener(auditLogService);
    private AuditLogTestSupport audit;

    @BeforeEach
    void setUp() {
        audit = new AuditLogTestSupport();
    }

    @AfterEach
    void tearDown() {
        audit.close();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static void currentRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void formLoginSuccess_isAudited() {
        currentRequest("/login");

        listener.onSuccess(new AuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of())));

        assertThat(audit.messages()).containsExactly("event=LOGIN_SUCCESS method=FORM user=alice ip=10.0.0.7");
    }

    @Test
    void apiLoginFailure_isAudited_withoutPassword_andWithSanitizedUsername() {
        currentRequest("/api/v1/auth/login");
        var attempt = UsernamePasswordAuthenticationToken.unauthenticated(
                "mallory\r\nevent=LOGIN_SUCCESS user=admin", "S3cret-password");

        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(attempt, new BadCredentialsException("x")));

        assertThat(audit.messages()).singleElement().satisfies(line -> {
            assertThat(line).startsWith("event=LOGIN_FAILURE method=API user=mallory_event=LOGIN_SUCCESS_user=admin");
            assertThat(line).endsWith("reason=BAD_CREDENTIALS ip=10.0.0.7");
            assertThat(line).doesNotContain("S3cret-password").doesNotContain("\n").doesNotContain("\r");
        });
    }

    @Test
    void disabledAccount_isAuditedWithReason() {
        currentRequest("/login");

        listener.onFailure(new AuthenticationFailureDisabledEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("bob", "pw"), new DisabledException("locked")));

        assertThat(audit.messages()).containsExactly(
                "event=LOGIN_FAILURE method=FORM user=bob reason=DISABLED ip=10.0.0.7");
    }

    @Test
    void rememberMeLogin_isAuditedAsRememberMe() {
        currentRequest("/profile");

        listener.onSuccess(new AuthenticationSuccessEvent(new RememberMeAuthenticationToken(
                "key", "carol", AuthorityUtils.createAuthorityList("ROLE_USER"))));

        assertThat(audit.messages()).containsExactly("event=LOGIN_SUCCESS method=REMEMBER_ME user=carol ip=10.0.0.7");
    }

    @Test
    void oauth2Failure_isAudited_andRedirectsToLoginError() throws Exception {
        var handler = new OAuth2LoginFailureHandler(auditLogService);
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setRemoteAddr("10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "user denied", null)));

        assertThat(audit.messages()).containsExactly(
                "event=LOGIN_FAILURE method=OAUTH2:google user=- reason=access_denied ip=10.0.0.8");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=true");
    }

    @Test
    void adminChanges_recordActorFromSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));

        auditLogService.userRoleChanged(5L, "dave", Role.ROLE_USER, Role.ROLE_ADMIN);
        auditLogService.userActiveChanged(5L, "dave", false);
        auditLogService.userPasswordReset(5L, "dave");

        assertThat(audit.messages()).containsExactly(
                "event=USER_ROLE_CHANGED actor=admin targetId=5 target=dave from=ROLE_USER to=ROLE_ADMIN ip=-",
                "event=USER_LOCKED actor=admin targetId=5 target=dave ip=-",
                "event=USER_PASSWORD_RESET actor=admin targetId=5 target=dave ip=-");
    }

    @Test
    void orderStatusChange_isLoggedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditLogService.orderStatusChanged(9L, OrderStatus.PENDING, OrderStatus.PROCESSING);
            assertThat(audit.messages()).isEmpty();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(audit.messages()).containsExactly(
                "event=ORDER_STATUS_CHANGED actor=system orderId=9 from=PENDING to=PROCESSING ip=-");
    }

    @Test
    void reason_isDerivedFromEventClassName() {
        var event = new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("x", "y"), new BadCredentialsException("x"));
        assertThat(AuthenticationAuditListener.reason(event)).isEqualTo("BAD_CREDENTIALS");
    }
}
