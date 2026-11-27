package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Frame ERROR có thông điệp cố định — không lặp lại token CSRF/chi tiết exception — và lần bị từ chối được audit. */
class StompErrorHandlerTest {

    private static final String SECRET_TOKEN = "client-sent-csrf-token-123";

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final StompErrorHandler handler = new StompErrorHandler(auditLogService, new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    });

    @Test
    void invalidCsrfToken_returnsAccessDenied_withoutEchoingToken() {
        Message<byte[]> connect = clientMessage(StompCommand.CONNECT, null);
        InvalidCsrfTokenException csrf = new InvalidCsrfTokenException(
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "expected-token"), SECRET_TOKEN);

        Message<byte[]> error = handler.handleClientMessageProcessingError(connect,
                new MessageDeliveryException(connect, csrf));

        StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
        assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(headers.getMessage()).isEqualTo(StompErrorHandler.ACCESS_DENIED);
        assertThat(headers.toNativeHeaderMap().toString()).doesNotContain(SECRET_TOKEN, "expected-token");
        assertThat(new String(error.getPayload(), StandardCharsets.UTF_8)).doesNotContain(SECRET_TOKEN);
        verify(auditLogService).webSocketAccessDenied("alice", "CONNECT", "-", "INVALID_CSRF_TOKEN", 0L);
    }

    @Test
    void deniedSubscribe_isAudited_withSanitizedDestination() {
        Message<byte[]> subscribe = clientMessage(StompCommand.SUBSCRIBE, "/topic/admin/orders\r\nfake");

        Message<byte[]> error = handler.handleClientMessageProcessingError(subscribe,
                new MessageDeliveryException(subscribe, new AccessDeniedException("Access Denied")));

        assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo(StompErrorHandler.ACCESS_DENIED);
        verify(auditLogService).webSocketAccessDenied("alice", "SUBSCRIBE", "/topic/admin/orders_fake", "ACCESS_DENIED", 0L);
    }

    @Test
    void otherErrors_returnGenericMessage_withoutExceptionDetails() {
        Message<byte[]> subscribe = clientMessage(StompCommand.SUBSCRIBE, "/topic/admin/orders");

        Message<byte[]> error = handler.handleClientMessageProcessingError(subscribe,
                new IllegalStateException("internal detail: jdbc:mysql://db"));

        StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
        assertThat(headers.getMessage()).isEqualTo(StompErrorHandler.PROCESSING_FAILED);
        assertThat(headers.toNativeHeaderMap().toString()).doesNotContain("internal detail");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void receiptOfClientFrame_isEchoed() {
        StompHeaderAccessor client = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        client.setReceipt("r-1");
        Message<byte[]> subscribe = MessageBuilder.createMessage(new byte[0], client.getMessageHeaders());

        Message<byte[]> error = handler.handleClientMessageProcessingError(subscribe, new AccessDeniedException("x"));

        assertThat(StompHeaderAccessor.wrap(error).getReceiptId()).isEqualTo("r-1");
    }

    @Test
    void floodOfDeniedFrames_isAuditedAtMostFiveTimesPerMinutePerUser_thenReportsSuppressedCount() {
        // Một message WebSocket chứa được hàng trăm frame: Spring xử lý tiếp các frame sau frame lỗi đầu tiên
        for (int i = 0; i < 200; i++) {
            Message<byte[]> error = handler.handleClientMessageProcessingError(
                    clientMessage(StompCommand.SEND, "/topic/admin/orders"), new AccessDeniedException("x"));
            // Client vẫn nhận ERROR cố định cho mọi frame — chỉ việc ghi log bị giới hạn
            assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo(StompErrorHandler.ACCESS_DENIED);
        }
        verify(auditLogService, times(StompErrorHandler.MAX_LOGGED_PER_WINDOW))
                .webSocketAccessDenied("alice", "SEND", "/topic/admin/orders", "ACCESS_DENIED", 0L);

        // User khác có hạn mức riêng
        handler.handleClientMessageProcessingError(clientMessage("bob", StompCommand.SEND, "/topic/admin/orders"),
                new AccessDeniedException("x"));
        verify(auditLogService).webSocketAccessDenied("bob", "SEND", "/topic/admin/orders", "ACCESS_DENIED", 0L);

        // Cửa sổ mới: ghi lại, kèm số lần đã bỏ qua để không mất dấu vết
        clearInvocations(auditLogService);
        now.set(now.get().plus(StompErrorHandler.LOG_WINDOW));
        handler.handleClientMessageProcessingError(clientMessage(StompCommand.SEND, "/topic/admin/orders"),
                new AccessDeniedException("x"));
        verify(auditLogService).webSocketAccessDenied("alice", "SEND", "/topic/admin/orders", "ACCESS_DENIED",
                200L - StompErrorHandler.MAX_LOGGED_PER_WINDOW);
    }

    @Test
    void otherErrors_shareTheSameLimit() {
        for (int i = 0; i < StompErrorHandler.MAX_LOGGED_PER_WINDOW; i++) {
            handler.handleClientMessageProcessingError(clientMessage(StompCommand.SUBSCRIBE, "/topic/x"),
                    new IllegalStateException("too many subscriptions"));
        }

        handler.handleClientMessageProcessingError(clientMessage(StompCommand.SUBSCRIBE, "/topic/admin/orders"),
                new AccessDeniedException("x"));

        verify(auditLogService, never()).webSocketAccessDenied(anyString(), anyString(), anyString(), anyString(),
                anyLong());
    }

    private static Message<byte[]> clientMessage(StompCommand command, String destination) {
        return clientMessage("alice", command, destination);
    }

    private static Message<byte[]> clientMessage(String username, StompCommand command, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setUser(new TestingAuthenticationToken(username, "n/a", "ROLE_USER"));
        if (destination != null) {
            headers.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
