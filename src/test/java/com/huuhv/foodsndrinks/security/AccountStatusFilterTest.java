package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AccountStatusFilter}: trước khi huỷ session vì đổi mật khẩu, đọc lại mốc xác thực trong kho session — request
 * song song của chính session vừa đổi mật khẩu đã nạp bản session cũ hơn bản trong kho.
 */
class AccountStatusFilterTest {

    private static final Instant LOGGED_IN_AT = Instant.parse("2026-11-28T01:00:00Z");
    private static final Instant PASSWORD_CHANGED_AT = Instant.parse("2026-11-28T02:00:00Z");

    private final AccountStatusService accountStatusService = mock(AccountStatusService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final SessionAuthenticationTimeStore store = mock(SessionAuthenticationTimeStore.class);
    private final AccountStatusFilter filter = new AccountStatusFilter(accountStatusService, auditLogService,
            mock(LogoutHandler.class), store);

    private final Authentication alice = UsernamePasswordAuthenticationToken.authenticated("alice", null,
            AuthorityUtils.createAuthorityList("ROLE_USER"));
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(alice);
        request = new MockHttpServletRequest("GET", "/orders");
        MockHttpSession session = new MockHttpSession(null, "session-1");
        session.setAttribute(SessionAuthenticationTime.ATTRIBUTE, LOGGED_IN_AT.toEpochMilli());
        request.setSession(session);
        when(accountStatusService.sessionRevocationReason(eq("alice"), any(), eq(LOGGED_IN_AT)))
                .thenReturn(Optional.of(AccountStatusService.PASSWORD_CHANGED));
        when(accountStatusService.sessionRevocationReason(eq("alice"), any(), eq(PASSWORD_CHANGED_AT)))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void staleSnapshotOfTheSessionThatChangedThePassword_isNotRevoked() throws Exception {
        when(store.reload("session-1")).thenReturn(PASSWORD_CHANGED_AT);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(auditLogService, never()).sessionRevoked(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(alice);
    }

    @Test
    void anotherSession_withTheSameOldStampInTheStore_isRevoked() throws Exception {
        when(store.reload("session-1")).thenReturn(LOGGED_IN_AT);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(auditLogService).sessionRevoked("alice", AccountStatusService.PASSWORD_CHANGED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void otherRevocationReasons_doNotReadTheSessionStore() throws Exception {
        when(accountStatusService.sessionRevocationReason(eq("alice"), any(), eq(LOGGED_IN_AT)))
                .thenReturn(Optional.of(AccountStatusService.DISABLED));

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(auditLogService).sessionRevoked("alice", AccountStatusService.DISABLED);
        verifyNoInteractions(store);
    }
}
