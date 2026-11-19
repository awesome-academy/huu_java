package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quyết định session web đang đăng nhập còn được dùng tiếp hay phải huỷ, dựa trên trạng thái hiện tại của tài khoản
 * trong DB (xem {@code security.AccountStatusFilter}).
 * <p>
 * Session giữ {@code Authentication} được tạo lúc đăng nhập; nếu không kiểm tra lại, tài khoản bị admin khoá hoặc
 * bị hạ quyền (ADMIN → USER) vẫn dùng session cũ với quyền cũ tới khi session hết hạn.
 */
@Service
@RequiredArgsConstructor
public class AccountStatusService {

    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String DISABLED = "DISABLED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";

    private static final String ROLE_PREFIX = "ROLE_";

    private final UserRepository userRepository;

    /**
     * @param username    {@code Authentication#getName()} — username local với mọi cách đăng nhập (form, remember-me,
     *                    Google, Facebook, X)
     * @param authorities quyền đang nằm trong session; chỉ so các quyền {@code ROLE_*} (Spring Security có thể thêm
     *                    quyền khác, vd. factor authority)
     * @return lý do phải huỷ session ({@link #ACCOUNT_NOT_FOUND} — tài khoản bị xoá/đổi username, {@link #DISABLED},
     *         {@link #ROLE_CHANGED}); rỗng nếu session còn hợp lệ
     */
    @Transactional(readOnly = true)
    public Optional<String> sessionRevocationReason(String username, Collection<? extends GrantedAuthority> authorities) {
        Optional<UserAccountStatus> status = userRepository.findAccountStatusByUsername(username);
        if (status.isEmpty()) {
            return Optional.of(ACCOUNT_NOT_FOUND);
        }
        if (Boolean.FALSE.equals(status.get().getActive())) {
            return Optional.of(DISABLED);
        }
        Set<String> sessionRoles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
                .collect(Collectors.toSet());
        if (status.get().getRole() == null || !sessionRoles.equals(Set.of(status.get().getRole().name()))) {
            return Optional.of(ROLE_CHANGED);
        }
        return Optional.empty();
    }
}
