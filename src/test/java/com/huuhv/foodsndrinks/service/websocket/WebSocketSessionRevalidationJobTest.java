package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Job thu hồi kết nối WebSocket: tài khoản bị khoá/đổi role/xoá → đóng + audit; session HTTP kết thúc → đóng; còn hợp lệ
 * → giữ. Một query trạng thái cho mọi kết nối.
 */
class WebSocketSessionRevalidationJobTest {

    private final WebSocketSessionTracker tracker = new WebSocketSessionTracker();
    private final WebSocketHandler decorated = tracker.decorate(mock(WebSocketHandler.class));
    private final HttpSessionLookup httpSessionLookup = mock(HttpSessionLookup.class);
    private final AccountStatusService accountStatusService = mock(AccountStatusService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final WebSocketSessionRevalidationJob job =
            new WebSocketSessionRevalidationJob(tracker, httpSessionLookup, accountStatusService, auditLogService);

    private record Status(Boolean getActive, Role getRole) implements UserAccountStatus {
    }

    @Test
    void noOpenSessions_doesNotQueryDatabase() {
        assertThat(job.revalidateOpenSessions()).isZero();
        verifyNoInteractions(accountStatusService);
    }

    @Test
    void validSessions_areKept() throws Exception {
        WebSocketSession alice = open("ws-1", "alice", "ROLE_USER");
        when(httpSessionLookup.isAlive(any())).thenReturn(true);
        when(accountStatusService.findAccountStatuses(anyCollection()))
                .thenReturn(Map.of("alice", new Status(true, Role.ROLE_USER)));

        assertThat(job.revalidateOpenSessions()).isZero();

        verify(alice, never()).close(any(CloseStatus.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void lockedDemotedOrDeletedAccounts_areClosedAndAudited_inOneQuery() throws Exception {
        WebSocketSession locked = open("ws-1", "locked", "ROLE_USER");
        WebSocketSession demoted = open("ws-2", "demoted", "ROLE_ADMIN");
        WebSocketSession deleted = open("ws-3", "deleted", "ROLE_USER");
        WebSocketSession ok = open("ws-4", "ok", "ROLE_ADMIN");
        when(httpSessionLookup.isAlive(any())).thenReturn(true);
        when(accountStatusService.findAccountStatuses(anyCollection())).thenReturn(Map.of(
                "locked", new Status(false, Role.ROLE_USER),
                "demoted", new Status(true, Role.ROLE_USER),
                "ok", new Status(true, Role.ROLE_ADMIN)));

        assertThat(job.revalidateOpenSessions()).isEqualTo(3);

        verify(locked).close(WebSocketSessionTracker.REVOKED);
        verify(demoted).close(WebSocketSessionTracker.REVOKED);
        verify(deleted).close(WebSocketSessionTracker.REVOKED);
        verify(ok, never()).close(any(CloseStatus.class));
        verify(auditLogService).webSocketSessionRevoked("locked", AccountStatusService.DISABLED);
        verify(auditLogService).webSocketSessionRevoked("demoted", AccountStatusService.ROLE_CHANGED);
        verify(auditLogService).webSocketSessionRevoked("deleted", AccountStatusService.ACCOUNT_NOT_FOUND);
        verify(accountStatusService, times(1)).findAccountStatuses(anyCollection());
        assertThat(tracker.snapshot()).extracting(WebSocketSessionTracker.TrackedSession::username).containsExactly("ok");
    }

    @Test
    void endedHttpSession_isClosed_withoutAudit() throws Exception {
        WebSocketSession loggedOut = open("ws-1", "alice", "ROLE_USER");
        when(httpSessionLookup.isAlive(loggedOut)).thenReturn(false);
        when(accountStatusService.findAccountStatuses(anyCollection()))
                .thenReturn(Map.of("alice", new Status(true, Role.ROLE_USER)));

        assertThat(job.revalidateOpenSessions()).isEqualTo(1);

        verify(loggedOut).close(WebSocketSessionTracker.REVOKED);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void databaseFailure_keepsSessions_andDoesNotThrow() throws Exception {
        WebSocketSession alice = open("ws-1", "alice", "ROLE_USER");
        when(accountStatusService.findAccountStatuses(anyCollection())).thenThrow(new IllegalStateException("db down"));

        assertThatNoException().isThrownBy(job::revalidate);

        verify(alice, never()).close(any(CloseStatus.class));
        assertThat(tracker.snapshot()).hasSize(1);
    }

    private WebSocketSession open(String id, String username, String role) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>(Map.of(HttpSessionLookup.HTTP_SESSION_ID_ATTRIBUTE, "http-" + id));
        when(socket.getId()).thenReturn(id);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getPrincipal()).thenReturn(new TestingAuthenticationToken(username, "n/a", List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(role))));
        when(socket.isOpen()).thenReturn(true);
        decorated.afterConnectionEstablished(socket);
        return socket;
    }
}
