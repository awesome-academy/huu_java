package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.utils.RequestUtils;
import com.huuhv.foodsndrinks.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ghi sự kiện bảo mật ra logger riêng {@code AUDIT}.
 * <p>
 * Mỗi sự kiện là một message ngắn (vd. "Login failed") kèm các trường gắn bằng SLF4J fluent API
 * ({@code addKeyValue}), không tự ghép chuỗi: prod ghi {@code logs/audit.log} dạng JSON (ECS) nên các trường được
 * escape đúng chuẩn; console và {@code foodsndrinks.log} in chúng theo kiểu logfmt qua {@code %safeKvp}.
 * Tên trường dùng dấu chấm ({@code event.action}, {@code target.id}...) để ECS lồng thành object; không dùng tên trùng
 * với key MDC ({@code requestId}, {@code clientRequestId}, {@code user.name}) — trùng thì encoder ECS báo lỗi.
 * <p>
 * Chỉ ghi định danh (username, id), hành động, IP client — KHÔNG BAO GIỜ ghi password, token, JWT, session id.
 * Username nhập khi đăng nhập thất bại chỉ được ghi nếu tài khoản tồn tại, để mật khẩu lỡ gõ vào ô username không
 * nằm lại trong log. Sự kiện thay đổi dữ liệu chỉ được ghi sau khi transaction commit.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final String SYSTEM_ACTOR = "system";
    private static final String UNKNOWN = "-";
    /**
     * Giới hạn độ dài một trường audit = độ dài cột {@code users.email} (định danh dài nhất được ghi). Cắt ngắn hơn
     * (vd. 64 như MDC {@code user}) sẽ làm hai email dài cùng tiền tố không còn phân biệt được trong audit log.
     */
    static final int MAX_FIELD_LENGTH = 255;
    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern EXCEPTION_SUFFIX = Pattern.compile("Exception$");

    private final UserRepository userRepository;

    // -------------------------------------------------------
    // Authentication
    // -------------------------------------------------------

    /** @param method kênh đăng nhập: FORM, API, REMEMBER_ME, OAUTH2:&lt;provider&gt; */
    public void loginSuccess(String method, String username) {
        write(event(Level.INFO, "Login succeeded", "LOGIN_SUCCESS", "success")
                .with("auth.method", method)
                .with("username", username));
    }

    /**
     * @param attemptedUsername username người dùng đã nhập; chỉ được ghi nếu tài khoản tồn tại, ngược lại ghi
     *                          {@code -}. Null nếu không có (OAuth2)
     * @param reason            mã lỗi ngắn, vd. BAD_CREDENTIALS, DISABLED, INVALID_COOKIE, access_denied
     */
    public void loginFailure(String method, String attemptedUsername, String reason) {
        write(event(Level.WARN, "Login failed", "LOGIN_FAILURE", "failure")
                .with("event.reason", reason)
                .with("auth.method", method)
                .with("username", existingAccountOrUnknown(attemptedUsername)));
    }

    /**
     * Tên đăng nhập bị tạm khoá vì sai quá nhiều lần (form hoặc API). Username chỉ được ghi nếu tài khoản tồn tại,
     * giống {@link #loginFailure}. Các lần đăng nhập tiếp theo trong thời gian khoá được ghi bằng
     * {@code loginFailure(..., "LOGIN_THROTTLED")}.
     */
    public void loginLockedOut(String attemptedUsername, String scope, int failures, Duration lockDuration) {
        write(event(Level.WARN, "Login locked out", "LOGIN_LOCKOUT", "failure")
                .with("username", existingAccountOrUnknown(attemptedUsername))
                .with("auth.failures", failures)
                .with("auth.lock.seconds", lockDuration.toSeconds())
                .with("auth.lock.scope", scope));
    }

    /**
     * Mọi lần đăng nhập từ một IP (IPv6: cả mạng /64) bị chặn vì sai quá nhiều lần với bất kỳ tên đăng nhập nào
     * (password spraying). IP nằm ở trường {@code source.ip} chung của mọi sự kiện.
     */
    public void loginSourceBlocked(int failures, Duration lockDuration) {
        write(event(Level.WARN, "Login source blocked", "LOGIN_LOCKOUT", "failure")
                .with("username", UNKNOWN)
                .with("auth.failures", failures)
                .with("auth.lock.seconds", lockDuration.toSeconds())
                .with("auth.lock.scope", "IP"));
    }

    /** Đăng ký tài khoản bị từ chối vì một IP gửi quá nhiều yêu cầu đăng ký. */
    public void registrationThrottled() {
        write(event(Level.WARN, "Registration throttled", "REGISTRATION_THROTTLED", "failure"));
    }

    /**
     * Session web đang đăng nhập bị huỷ vì tài khoản đã đổi trạng thái kể từ lúc đăng nhập.
     *
     * @param reason {@code DISABLED}, {@code ROLE_CHANGED}, {@code ACCOUNT_NOT_FOUND} (xem {@code AccountStatusService})
     */
    public void sessionRevoked(String username, String reason) {
        write(event(Level.WARN, "Session revoked", "SESSION_REVOKED", "success")
                .with("event.reason", reason)
                .with("username", username));
    }

    /**
     * Kết nối WebSocket đang mở bị đóng vì tài khoản đã đổi trạng thái kể từ lúc handshake (job kiểm tra định kỳ, không
     * có request HTTP nên không có {@code source.ip}).
     *
     * @param reason như {@link #sessionRevoked}
     */
    public void webSocketSessionRevoked(String username, String reason) {
        write(event(Level.WARN, "WebSocket session revoked", "SESSION_REVOKED", "success")
                .with("event.reason", reason)
                .with("session.type", "websocket")
                .with("username", username));
    }

    /**
     * Frame STOMP bị từ chối: phân quyền destination (vd. user thường SUBSCRIBE kênh admin, client SEND vào broker) hoặc
     * thiếu/sai token CSRF ở frame CONNECT. Thread xử lý frame không có MDC {@code user.name} nên username ghi tường minh.
     *
     * @param command     lệnh STOMP (CONNECT, SUBSCRIBE, SEND...)
     * @param destination đích của frame (đã cắt ngắn, bỏ ký tự ngắt dòng), {@code -} nếu không có
     * @param reason      mã lỗi, vd. ACCESS_DENIED, INVALID_CSRF_TOKEN, MISSING_CSRF_TOKEN
     */
    public void webSocketAccessDenied(String username, String command, String destination, String reason) {
        write(event(Level.WARN, "WebSocket frame denied", "WEBSOCKET_ACCESS_DENIED", "failure")
                .with("event.reason", reason)
                .with("stomp.command", command)
                .with("stomp.destination", destination)
                .with("username", username));
    }

    // -------------------------------------------------------
    // Account changes (admin or the user themself)
    // -------------------------------------------------------

    public void userActiveChanged(Long userId, String username, boolean active) {
        String action = active ? "USER_UNLOCKED" : "USER_LOCKED";
        writeAfterCommit(accountEvent(Level.INFO, active ? "User unlocked" : "User locked", action, userId, username));
    }

    public void userRoleChanged(Long userId, String username, Role from, Role to) {
        writeAfterCommit(accountEvent(Level.INFO, "User role changed", "USER_ROLE_CHANGED", userId, username)
                .with("target.role.from", from)
                .with("target.role.to", to));
    }

    /** Admin đặt mật khẩu mới cho user. */
    public void userPasswordReset(Long userId, String username) {
        writeAfterCommit(accountEvent(Level.INFO, "User password reset", "USER_PASSWORD_RESET", userId, username));
    }

    /** Email đăng nhập của tài khoản bị đổi (admin sửa hoặc user tự sửa — xem {@code actor}). */
    public void userEmailChanged(Long userId, String username) {
        writeAfterCommit(accountEvent(Level.INFO, "User email changed", "USER_EMAIL_CHANGED", userId, username));
    }

    /** User tự đổi mật khẩu ở trang hồ sơ. */
    public void passwordChanged(Long userId, String username) {
        writeAfterCommit(accountEvent(Level.INFO, "Password changed", "PASSWORD_CHANGED", userId, username));
    }

    /**
     * User đổi mật khẩu thất bại (vd. sai mật khẩu hiện tại — dấu hiệu dò mật khẩu bằng session bị chiếm).
     * Ghi ngay, không đợi commit, vì thao tác thất bại làm transaction rollback.
     */
    public void passwordChangeFailed(Long userId, String username, String reason) {
        write(accountEvent(Level.WARN, "Password change failed", "PASSWORD_CHANGE_FAILED", userId, username)
                .with("event.reason", reason));
    }

    /** Nhập sai mật khẩu hiện tại quá nhiều lần khi đổi mật khẩu → tạm khoá đổi mật khẩu và huỷ session. */
    public void passwordChangeLockedOut(Long userId, String username, int failures, Duration lockDuration) {
        write(accountEvent(Level.WARN, "Password change locked out", "PASSWORD_CHANGE_LOCKOUT", userId, username)
                .with("auth.failures", failures)
                .with("auth.lock.seconds", lockDuration.toSeconds()));
    }

    // -------------------------------------------------------
    // Admin: orders
    // -------------------------------------------------------

    public void orderStatusChanged(Long orderId, OrderStatus from, OrderStatus to) {
        writeAfterCommit(event(Level.INFO, "Order status changed", "ORDER_STATUS_CHANGED", "success")
                .with("actor", currentActor())
                .with("order.id", orderId)
                .with("order.status.from", from)
                .with("order.status.to", to));
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    /**
     * Mã lý do lấy từ tên class exception — dùng chung cho mọi kênh đăng nhập để cùng một loại lỗi luôn ra cùng một
     * mã: {@code BadCredentialsException} → {@code BAD_CREDENTIALS}, {@code DisabledException} → {@code DISABLED},
     * {@code InvalidCookieException} → {@code INVALID_COOKIE}.
     */
    public static String toReasonCode(Throwable exception) {
        return toReasonCode(EXCEPTION_SUFFIX.matcher(exception.getClass().getSimpleName()).replaceFirst(""));
    }

    /** {@code BadCredentials} → {@code BAD_CREDENTIALS}, {@code InvalidCookie} → {@code INVALID_COOKIE}. */
    static String toReasonCode(String camelCase) {
        return CAMEL_CASE_BOUNDARY.matcher(camelCase).replaceAll("$1_$2").toUpperCase(Locale.ROOT);
    }

    private static AuditEvent event(Level level, String message, String action, String outcome) {
        return new AuditEvent(level, message)
                .with("event.action", action)
                .with("event.outcome", outcome);
    }

    private static AuditEvent accountEvent(Level level, String message, String action, Long userId, String username) {
        String outcome = level == Level.WARN ? "failure" : "success";
        return event(level, message, action, outcome)
                .with("actor", currentActor())
                .with("target.id", userId)
                .with("target.username", username);
    }

    private static void write(AuditEvent event) {
        LoggingEventBuilder builder = AUDIT.atLevel(event.level);
        for (Map.Entry<String, Object> field : event.fields.entrySet()) {
            builder = builder.addKeyValue(field.getKey(), field.getValue());
        }
        builder.addKeyValue("source.ip", event.sourceIp).log(event.message);
    }

    /** Các trường được tính ngay (actor/IP của thread hiện tại); chỉ việc ghi log được hoãn tới sau commit. */
    private static void writeAfterCommit(AuditEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(event);
                }
            });
        } else {
            write(event);
        }
    }

    private String existingAccountOrUnknown(String attemptedUsername) {
        if (attemptedUsername == null || attemptedUsername.isBlank()) {
            return UNKNOWN;
        }
        // Một query (cùng điều kiện với CustomUserDetailsService) để không tăng tải DB và không làm lệch thời gian phản
        // hồi giữa username có / không tồn tại
        return userRepository.existsByUsernameOrEmail(attemptedUsername, attemptedUsername)
                ? attemptedUsername : UNKNOWN;
    }

    private static String currentActor() {
        return SecurityUtils.currentUsername().orElse(SYSTEM_ACTOR);
    }

    /** IP client thật (xem {@link RequestUtils#currentClientIp()}). */
    private static String clientIp() {
        return RequestUtils.currentClientIp().orElse(UNKNOWN);
    }

    /** Message + các trường của một sự kiện audit; chuỗi được cắt về {@link #MAX_FIELD_LENGTH}, enum ghi theo tên. */
    private static final class AuditEvent {

        private final Level level;
        private final String message;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        /** Lấy ngay lúc tạo sự kiện (vẫn đang trong request), luôn được ghi làm trường cuối. */
        private final String sourceIp = clientIp();

        private AuditEvent(Level level, String message) {
            this.level = level;
            this.message = message;
        }

        private AuditEvent with(String key, Object value) {
            Object normalized = switch (value) {
                case null -> UNKNOWN;
                case String text -> text.isBlank() ? UNKNOWN : LogSanitizer.truncate(text, MAX_FIELD_LENGTH);
                case Enum<?> constant -> constant.name();
                default -> value;
            };
            fields.put(key, normalized);
            return this;
        }
    }
}
