package com.huuhv.foodsndrinks.service.websocket;

import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

    private final WebSocketSessionTracker tracker =
            new WebSocketSessionTracker(WebSocketSessionTrackerTest.properties(10), Runnable::run);
    private final WebSocketHandler decorated = tracker.decorate(mock(WebSocketHandler.class));
    private final HttpSessionLookup httpSessionLookup = mock(HttpSessionLookup.class);
    private final AccountStatusService accountStatusService = mock(AccountStatusService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final WebSocketSessionRevalidationJob job =
            new WebSocketSessionRevalidationJob(tracker, httpSessionLookup, accountStatusService, auditLogService);

    private static final Instant PASSWORD_CHANGED_AT = Instant.parse("2026-11-28T03:00:00Z");

    private record Status(Boolean getActive, Role getRole, Instant getPasswordChangedAt) implements UserAccountStatus {
        /** Tài khoản chưa từng đổi mật khẩu. */
        Status(Boolean active, Role role) {
            this(active, role, null);
        }
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
    void socketsOfSessionsAuthenticatedBeforeThePasswordChange_areClosedAndAudited() throws Exception {
        // Cùng một user, ba trình duyệt: đăng nhập trước khi đổi mật khẩu, session vừa đổi mật khẩu (mốc = lúc đổi),
        // và session không mang mốc xác thực (coi như cũ)
        WebSocketSession oldLogin = open("ws-1", "alice", "ROLE_USER", PASSWORD_CHANGED_AT.minusSeconds(60));
        WebSocketSession changer = open("ws-2", "alice", "ROLE_USER", PASSWORD_CHANGED_AT);
        WebSocketSession unstamped = open("ws-3", "alice", "ROLE_USER");
        WebSocketSession otherUser = open("ws-4", "bob", "ROLE_USER", PASSWORD_CHANGED_AT.minusSeconds(60));
        when(httpSessionLookup.isAlive(any())).thenReturn(true);
        when(accountStatusService.findAccountStatuses(anyCollection())).thenReturn(Map.of(
                "alice", new Status(true, Role.ROLE_USER, PASSWORD_CHANGED_AT),
                "bob", new Status(true, Role.ROLE_USER)));

        assertThat(job.revalidateOpenSessions()).isEqualTo(2);

        verify(oldLogin).close(WebSocketSessionTracker.REVOKED);
        verify(unstamped).close(WebSocketSessionTracker.REVOKED);
        verify(changer, never()).close(any(CloseStatus.class));
        verify(otherUser, never()).close(any(CloseStatus.class));
        verify(auditLogService, times(2)).webSocketSessionRevoked("alice", AccountStatusService.PASSWORD_CHANGED);
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
    void failedClose_isRetriedNextCycle_butAuditedOnlyOnce() throws Exception {
        WebSocketSession locked = open("ws-1", "locked", "ROLE_USER");
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).doAnswer(closesLikeContainer(locked))
                .when(locked).close(any(CloseStatus.class));
        when(httpSessionLookup.isAlive(any())).thenReturn(true);
        when(accountStatusService.findAccountStatuses(anyCollection()))
                .thenReturn(Map.of("locked", new Status(false, Role.ROLE_USER)));

        job.revalidateOpenSessions();
        assertThat(tracker.snapshot()).singleElement()
                .extracting(WebSocketSessionTracker.TrackedSession::revoked).isEqualTo(true);

        job.revalidateOpenSessions();

        verify(locked, times(2)).close(WebSocketSessionTracker.REVOKED);
        verify(auditLogService, times(1)).webSocketSessionRevoked("locked", AccountStatusService.DISABLED);
        // Chu kỳ 2 chỉ còn socket đã thu hồi → không query DB
        verify(accountStatusService, times(1)).findAccountStatuses(anyCollection());
    }

    @Test
    void socketOfEndedSession_whoseCloseFailed_isClosedAgainNextCycle_withoutAskingAgainWhy() throws Exception {
        // Session HTTP kết thúc ở instance khác (logout, đổi id khi đăng nhập lại): chỉ job này phát hiện được
        WebSocketSession socket = open("ws-1", "alice", "ROLE_USER");
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).doAnswer(closesLikeContainer(socket))
                .when(socket).close(any(CloseStatus.class));
        when(httpSessionLookup.isAlive(socket)).thenReturn(false);
        when(accountStatusService.findAccountStatuses(anyCollection()))
                .thenReturn(Map.of("alice", new Status(true, Role.ROLE_USER)));

        assertThat(job.revalidateOpenSessions()).isEqualTo(1);
        assertThat(tracker.snapshot()).singleElement()
                .extracting(WebSocketSessionTracker.TrackedSession::revoked).isEqualTo(true);

        assertThat(job.revalidateOpenSessions()).isEqualTo(1);

        verify(socket, times(2)).close(WebSocketSessionTracker.REVOKED);
        assertThat(tracker.snapshot()).isEmpty();
        // Chu kỳ 2 chỉ còn socket đã thu hồi → không hỏi lại kho session, không query DB, không audit
        verify(httpSessionLookup, times(1)).isAlive(socket);
        verify(accountStatusService, times(1)).findAccountStatuses(anyCollection());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void socketWhoseResyncCloseFailed_isClosedForResyncByJob_withoutAudit() throws Exception {
        WebSocketSession socket = open("ws-1", "alice", "ROLE_USER");
        WebSocketSession other = open("ws-2", "bob", "ROLE_USER");
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).doAnswer(closesLikeContainer(socket))
                .when(socket).close(any(CloseStatus.class));
        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));
        when(httpSessionLookup.isAlive(any())).thenReturn(true);
        when(accountStatusService.findAccountStatuses(anyCollection())).thenReturn(Map.of(
                "alice", new Status(true, Role.ROLE_USER)));

        assertThat(job.revalidateOpenSessions()).isEqualTo(1);

        verify(socket, times(2)).close(WebSocketSessionTracker.RESYNC);
        verify(socket, never()).close(WebSocketSessionTracker.REVOKED);
        assertThat(tracker.snapshot()).isEmpty();
        verifyNoInteractions(auditLogService);
        // ws-2 đã đóng được ngay lúc sự kiện → job không đụng lại
        verify(other, times(1)).close(any(CloseStatus.class));
    }

    @Test
    void resyncPendingSocketOfLockedAccount_isRevoked_notResynced() throws Exception {
        WebSocketSession socket = open("ws-1", "locked", "ROLE_USER");
        doThrow(new IllegalStateException("TEXT_FULL_WRITING")).doAnswer(closesLikeContainer(socket))
                .when(socket).close(any(CloseStatus.class));
        tracker.onRealtimeSubscriptionRestored(new RealtimeSubscriptionRestoredEvent("order.placed"));
        when(httpSessionLookup.isAlive(any())).thenReturn(true);
        when(accountStatusService.findAccountStatuses(anyCollection()))
                .thenReturn(Map.of("locked", new Status(false, Role.ROLE_USER)));

        assertThat(job.revalidateOpenSessions()).isEqualTo(1);

        verify(socket).close(WebSocketSessionTracker.REVOKED);
        verify(auditLogService).webSocketSessionRevoked("locked", AccountStatusService.DISABLED);
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
        return open(id, username, role, null);
    }

    /** @param authenticatedAt mốc xác thực của session HTTP lúc handshake, {@code null} = session không mang mốc */
    private WebSocketSession open(String id, String username, String role, Instant authenticatedAt) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>(Map.of(HttpSessionLookup.HTTP_SESSION_ID_ATTRIBUTE, "http-" + id));
        if (authenticatedAt != null) {
            attributes.put(HttpSessionLookup.AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        }
        when(socket.getId()).thenReturn(id);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getPrincipal()).thenReturn(new TestingAuthenticationToken(username, "n/a", List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(role))));
        when(socket.isOpen()).thenReturn(true);
        doAnswer(closesLikeContainer(socket)).when(socket).close(any(CloseStatus.class));
        decorated.afterConnectionEstablished(socket);
        return socket;
    }

    /** Như container: close() thành công → afterConnectionClosed (tracker gỡ bản ghi ở đó). */
    private Answer<Void> closesLikeContainer(WebSocketSession socket) {
        return invocation -> {
            when(socket.isOpen()).thenReturn(false);
            decorated.afterConnectionClosed(socket, invocation.getArgument(0));
            return null;
        };
    }
}
