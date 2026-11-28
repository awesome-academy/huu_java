package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import com.huuhv.foodsndrinks.repository.projection.UsernameAccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quyết định session web đang đăng nhập còn được dùng tiếp hay phải huỷ, dựa trên trạng thái hiện tại của tài khoản
 * trong DB (xem {@code security.AccountStatusFilter}; kết nối WebSocket đang mở: {@code WebSocketSessionRevalidationJob}).
 * <p>
 * Session giữ {@code Authentication} được tạo lúc đăng nhập; nếu không kiểm tra lại, tài khoản bị admin khoá hoặc
 * bị hạ quyền (ADMIN → USER) vẫn dùng session cũ với quyền cũ tới khi session hết hạn — và session mở bằng mật khẩu cũ
 * vẫn sống sau khi mật khẩu đã được đổi.
 */
@Service
@RequiredArgsConstructor
public class AccountStatusService {

    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String DISABLED = "DISABLED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    /** Mật khẩu được đổi (tự đổi hoặc admin đặt lại) sau khi session này đăng nhập. */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    /** Số username tối đa trong một câu {@code IN (...)}. */
    static final int BATCH_SIZE = 500;

    private static final String ROLE_PREFIX = "ROLE_";

    private final UserRepository userRepository;

    /**
     * @param username    {@code Authentication#getName()} — username local với mọi cách đăng nhập (form, remember-me,
     *                    Google, Facebook, X)
     * @param authorities quyền đang nằm trong session; chỉ so các quyền {@code ROLE_*} (Spring Security có thể thêm
     *                    quyền khác, vd. factor authority)
     * @param authenticatedAt mốc xác thực của session ({@code utils.SessionAuthenticationTime}), {@code null} nếu
     *                        session không mang mốc
     * @return lý do phải huỷ session ({@link #ACCOUNT_NOT_FOUND} — tài khoản bị xoá/đổi username, {@link #DISABLED},
     *         {@link #ROLE_CHANGED}, {@link #PASSWORD_CHANGED}); rỗng nếu session còn hợp lệ
     */
    @Transactional(readOnly = true)
    public Optional<String> sessionRevocationReason(String username, Collection<? extends GrantedAuthority> authorities,
                                                    Instant authenticatedAt) {
        return revocationReason(userRepository.findAccountStatusByUsername(username).orElse(null), authorities,
                authenticatedAt);
    }

    /**
     * Trạng thái hiện tại của nhiều tài khoản (một query mỗi {@value #BATCH_SIZE} username), để kiểm tra lại cùng lúc
     * nhiều phiên bằng {@link #revocationReason}. Username không có trong DB thì không có trong kết quả.
     */
    @Transactional(readOnly = true)
    public Map<String, UserAccountStatus> findAccountStatuses(Collection<String> usernames) {
        List<String> distinct = usernames.stream().distinct().toList();
        Map<String, UserAccountStatus> statuses = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += BATCH_SIZE) {
            List<String> batch = distinct.subList(from, Math.min(from + BATCH_SIZE, distinct.size()));
            for (UsernameAccountStatus status : userRepository.findAccountStatusesByUsernameIn(batch)) {
                statuses.put(status.getUsername(), status);
            }
        }
        return statuses;
    }

    /**
     * Quy tắc chung của {@link #sessionRevocationReason} cho trạng thái đã tra sẵn.
     *
     * <p>
     * Đổi mật khẩu: session (hoặc kết nối WebSocket của session) xác thực TRƯỚC {@code passwordChangedAt} bị huỷ — người
     * đổi mật khẩu thường vì nghi bị lộ, session của kẻ đang giữ mật khẩu cũ không được sống tiếp. Session không mang mốc
     * xác thực cũng bị coi là cũ (fail-closed). Session vừa tự đổi mật khẩu được ghi lại mốc = đúng {@code passwordChangedAt}
     * nên so "trước" (không phải "trước hoặc bằng") giữ nó lại. Tài khoản chưa từng đổi mật khẩu ({@code null}) — kể cả
     * tài khoản chỉ đăng nhập OAuth2 — không bị ảnh hưởng.
     *
     * @param status          trạng thái trong DB, {@code null} nếu không còn tài khoản
     * @param authenticatedAt mốc xác thực của session, {@code null} nếu không có
     */
    public static Optional<String> revocationReason(UserAccountStatus status,
                                                    Collection<? extends GrantedAuthority> authorities,
                                                    Instant authenticatedAt) {
        if (status == null) {
            return Optional.of(ACCOUNT_NOT_FOUND);
        }
        if (Boolean.FALSE.equals(status.getActive())) {
            return Optional.of(DISABLED);
        }
        Set<String> sessionRoles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
                .collect(Collectors.toSet());
        if (status.getRole() == null || !sessionRoles.equals(Set.of(status.getRole().name()))) {
            return Optional.of(ROLE_CHANGED);
        }
        Instant passwordChangedAt = status.getPasswordChangedAt();
        if (passwordChangedAt != null && (authenticatedAt == null || authenticatedAt.isBefore(passwordChangedAt))) {
            return Optional.of(PASSWORD_CHANGED);
        }
        return Optional.empty();
    }
}
