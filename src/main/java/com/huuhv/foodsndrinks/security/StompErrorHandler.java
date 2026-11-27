package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.logging.LogSanitizer;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Lỗi khi xử lý frame STOMP của client → frame ERROR với thông điệp CỐ ĐỊNH; Spring đóng kết nối sau đó.
 * <p>
 * Handler mặc định của Spring đưa {@code ex.getMessage()} vào header {@code message} của frame ERROR: với lỗi CSRF ở
 * frame CONNECT, thông điệp đó chứa lại token CSRF mà client gửi, với lỗi khác có thể chứa chi tiết nội bộ (OWASP:
 * không trả thông điệp của exception hệ thống ra ngoài).
 * <ul>
 *   <li>Bị từ chối ({@link AccessDeniedException}, gồm thiếu/sai token CSRF) → {@value #ACCESS_DENIED}, ghi audit
 *       {@code WEBSOCKET_ACCESS_DENIED} (lệnh, đích đã làm sạch, user).</li>
 *   <li>Lỗi khác → {@value #PROCESSING_FAILED}, log WARN kèm loại exception (không stack trace của frame client).</li>
 * </ul>
 * Mỗi user ghi tối đa {@value #MAX_LOGGED_PER_WINDOW} dòng (audit + WARN) mỗi phút trên mỗi instance — Spring xử lý tiếp
 * mọi frame còn lại của message WebSocket sau frame lỗi, client có thể nhồi hàng trăm frame bị từ chối vào một message
 * rồi handshake lại ({@link StompErrorLogThrottle}). Phần vượt chỉ log DEBUG; dòng kế tiếp mang số lần đã bỏ qua.
 */
@Slf4j
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    static final String ACCESS_DENIED = "Access denied";
    static final String PROCESSING_FAILED = "Message could not be processed";
    static final int MAX_LOGGED_PER_WINDOW = 5;
    static final Duration LOG_WINDOW = Duration.ofMinutes(1);
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final AuditLogService auditLogService;
    private final StompErrorLogThrottle throttle;

    public StompErrorHandler(AuditLogService auditLogService) {
        this(auditLogService, Clock.systemUTC());
    }

    StompErrorHandler(AuditLogService auditLogService, Clock clock) {
        this.auditLogService = auditLogService;
        this.throttle = new StompErrorLogThrottle(MAX_LOGGED_PER_WINDOW, LOG_WINDOW, clock);
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        StompHeaderAccessor clientHeaders = clientMessage == null ? null
                : MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
        AccessDeniedException denied = findAccessDenied(ex);
        String command = command(clientHeaders);
        String destination = clientHeaders == null ? "-" : LogSanitizer.sanitize(clientHeaders.getDestination());
        String username = username(clientMessage);
        String errorType = ex == null ? "-" : ex.getClass().getSimpleName();
        OptionalLong permit = throttle.tryAcquire(username);
        if (permit.isEmpty()) {
            log.debug("STOMP {} frame to {} from {} failed: {} (log rate limit reached)", command, destination,
                    username, errorType);
        } else if (denied != null) {
            auditLogService.webSocketAccessDenied(username, command, destination,
                    AuditLogService.toReasonCode(denied), permit.getAsLong());
        } else {
            log.warn("STOMP {} frame to {} from {} failed: {} ({} earlier errors not logged)", command, destination,
                    username, errorType, permit.getAsLong());
        }
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(denied != null ? ACCESS_DENIED : PROCESSING_FAILED);
        if (clientHeaders != null && clientHeaders.getReceipt() != null) {
            error.setReceiptId(clientHeaders.getReceipt());
        }
        error.setLeaveMutable(true);
        return handleInternal(error, EMPTY_PAYLOAD, ex, clientHeaders);
    }

    private static AccessDeniedException findAccessDenied(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException denied) {
                return denied;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    private static String command(StompHeaderAccessor headers) {
        StompCommand command = headers == null ? null : headers.getCommand();
        return command == null ? "-" : command.name();
    }

    private static String username(Message<?> clientMessage) {
        Principal user = clientMessage == null ? null : SimpMessageHeaderAccessor.getUser(clientMessage.getHeaders());
        return user == null ? "-" : LogSanitizer.sanitize(user.getName());
    }
}
