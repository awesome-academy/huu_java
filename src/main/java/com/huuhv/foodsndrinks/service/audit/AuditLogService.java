package com.huuhv.foodsndrinks.service.audit;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Ghi sự kiện bảo mật ra logger riêng {@code AUDIT} (prod: thêm file {@code logs/audit.log}, xem logback-spring.xml).
 * <p>
 * Định dạng {@code event=... key=value} để dễ grep/parse. Chỉ ghi định danh (username, id), hành động, IP client —
 * KHÔNG BAO GIỜ ghi password, token, JWT, session id. Mọi giá trị do người dùng kiểm soát đều qua
 * {@link LogSanitizer} (bỏ ký tự điều khiển/khoảng trắng, cắt độ dài) để không giả mạo được trường khác hay dòng log.
 * <p>
 * Sự kiện thay đổi dữ liệu (user, đơn hàng) chỉ được ghi sau khi transaction commit, để audit log không ghi nhận
 * một thay đổi đã bị rollback.
 */
@Service
public class AuditLogService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final String SYSTEM_ACTOR = "system";

    // -------------------------------------------------------
    // Authentication
    // -------------------------------------------------------

    /** @param method kênh đăng nhập: FORM, API, REMEMBER_ME, OAUTH2:&lt;provider&gt; */
    public void loginSuccess(String method, String username) {
        AUDIT.info("event=LOGIN_SUCCESS method={} user={} ip={}",
                LogSanitizer.sanitize(method), LogSanitizer.sanitize(username), clientIp());
    }

    /**
     * @param attemptedUsername username người dùng đã nhập (có thể không tồn tại); null nếu không có (OAuth2)
     * @param reason            mã lỗi ngắn, vd. BAD_CREDENTIALS, DISABLED, access_denied
     */
    public void loginFailure(String method, String attemptedUsername, String reason) {
        AUDIT.warn("event=LOGIN_FAILURE method={} user={} reason={} ip={}",
                LogSanitizer.sanitize(method), LogSanitizer.sanitize(attemptedUsername),
                LogSanitizer.sanitize(reason), clientIp());
    }

    // -------------------------------------------------------
    // Admin: user management
    // -------------------------------------------------------

    public void userActiveChanged(Long userId, String username, boolean active) {
        String event = active ? "USER_UNLOCKED" : "USER_LOCKED";
        logAfterCommit("event={} actor={} targetId={} target={} ip={}",
                event, currentActor(), userId, LogSanitizer.sanitize(username), clientIp());
    }

    public void userRoleChanged(Long userId, String username, Role from, Role to) {
        logAfterCommit("event=USER_ROLE_CHANGED actor={} targetId={} target={} from={} to={} ip={}",
                currentActor(), userId, LogSanitizer.sanitize(username), from, to, clientIp());
    }

    public void userPasswordReset(Long userId, String username) {
        logAfterCommit("event=USER_PASSWORD_RESET actor={} targetId={} target={} ip={}",
                currentActor(), userId, LogSanitizer.sanitize(username), clientIp());
    }

    // -------------------------------------------------------
    // Admin: orders
    // -------------------------------------------------------

    public void orderStatusChanged(Long orderId, OrderStatus from, OrderStatus to) {
        logAfterCommit("event=ORDER_STATUS_CHANGED actor={} orderId={} from={} to={} ip={}",
                currentActor(), orderId, from, to, clientIp());
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    /** Tham số được tính ngay (actor/IP của thread hiện tại); chỉ việc ghi log được hoãn tới sau commit. */
    private static void logAfterCommit(String format, Object... args) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    AUDIT.info(format, args);
                }
            });
        } else {
            AUDIT.info(format, args);
        }
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return SYSTEM_ACTOR;
        }
        return LogSanitizer.sanitize(auth.getName());
    }

    /**
     * IP kết nối trực tiếp tới app. Nếu sau này đặt app sau reverse proxy thì cấu hình
     * {@code server.forward-headers-strategy} để giá trị này là IP client thật.
     */
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return LogSanitizer.sanitize(attrs.getRequest().getRemoteAddr());
        }
        return "-";
    }
}
