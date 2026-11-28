package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.dto.request.ProfileUpdateReqDto;
import com.huuhv.foodsndrinks.dto.request.RegisterReqDto;
import com.huuhv.foodsndrinks.dto.request.UserEditReqDto;
import com.huuhv.foodsndrinks.dto.response.UserResDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.OAuthEmailConflictException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AuthenticationClock;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import com.huuhv.foodsndrinks.utils.SlugUtils;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final LoginAttemptService loginAttemptService;
    private final AuthenticationClock authenticationClock;

    // -------------------------------------------------------
    // Existence checks (used by registration)
    // -------------------------------------------------------

    public boolean existsByUsername(String username) { return userRepository.existsByUsername(username); }
    public boolean existsByEmail(String email)       { return userRepository.existsByEmail(email); }
    public boolean existsByPhone(String phone)       { return userRepository.existsByPhone(phone); }

    // -------------------------------------------------------
    // Registration
    // -------------------------------------------------------

    @Transactional
    public void registerNewUser(RegisterReqDto dto) {
        log.info("Registering new user: {}", dto.getUsername());
        var user = new User();
        user.setFullName(dto.getFullName());
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRole(Role.ROLE_USER);
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setIsActive(true);
        userRepository.save(user);
        log.info("User registered: {}", dto.getUsername());
    }

    // -------------------------------------------------------
    // OAuth2 login (Google/Facebook/Twitter) — find-or-create the local account
    // -------------------------------------------------------

    /**
     * Resolves the local {@link User} behind an OAuth2 identity, creating one on first login.
     * Match order: (provider, providerId) first, then by email — but only when linking cannot hand an account to
     * someone else (pre-account takeover):
     * <ul>
     *   <li>the provider must vouch for the email ({@code emailVerified}); and</li>
     *   <li>the existing account must have no password, i.e. it was itself created by an OAuth login. Local sign-up does
     *       not verify email, so an account with a password may have been registered by an attacker with the victim's
     *       email beforehand — linking would let the attacker keep signing in with that password.</li>
     * </ul>
     * Otherwise {@link OAuthEmailConflictException}. An unverified email is never stored on a new account (a placeholder
     * is used) so it cannot squat the address or be linked to later.
     *
     * @param email         provider-supplied email; null when the provider doesn't expose one (e.g. Twitter)
     * @param emailVerified whether the provider asserts the user controls {@code email}
     * @param fullName      provider-supplied display name; falls back to a generic label if blank
     * @param avatarUrl     provider-supplied profile picture URL, or null
     * @throws OAuthEmailConflictException if {@code email} belongs to an account that must not be linked
     */
    @Transactional
    public User provisionOAuthUser(AuthProvider provider, String providerId, String email, boolean emailVerified,
                                   String fullName, String avatarUrl) {
        User existing = userRepository.findByAuthProviderAndProviderId(provider, providerId).orElse(null);
        if (existing != null) return existing;

        if (!blank(email)) {
            User byEmail = userRepository.findByEmail(email).orElse(null);
            if (byEmail != null) {
                if (!emailVerified || !blank(byEmail.getPassword())) {
                    throw new OAuthEmailConflictException(byEmail.getUsername());
                }
                return byEmail;
            }
        }

        User user = new User();
        user.setUsername(generateUniqueUsername(fullName, provider, providerId));
        user.setEmail(!blank(email) && emailVerified ? email : placeholderEmail(provider, providerId));
        user.setFullName(!blank(fullName) ? fullName : "Người dùng " + provider.name());
        user.setAvatarUrl(avatarUrl);
        user.setRole(Role.ROLE_USER);
        user.setAuthProvider(provider);
        user.setProviderId(providerId);
        user.setIsActive(true);
        userRepository.save(user);
        log.info("Provisioned new {} user: {}", provider, user.getUsername());
        return user;
    }

    private String generateUniqueUsername(String fullName, AuthProvider provider, String providerId) {
        String base = SlugUtils.toSlug(fullName);
        if (blank(base)) base = provider.name().toLowerCase(Locale.ROOT) + "-" + providerId;

        String candidate = base;
        int i = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + "-" + (++i);
        }
        return candidate;
    }

    /** users.email is NOT NULL UNIQUE — synthesize a unique placeholder for providers (e.g. Twitter) that give no email. */
    private String placeholderEmail(AuthProvider provider, String providerId) {
        return provider.name().toLowerCase(Locale.ROOT) + "-" + providerId + "@no-email.fnbstore.local";
    }

    // -------------------------------------------------------
    // Web: resolve logged-in principal → domain User
    // -------------------------------------------------------

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public User getCurrentUser(String usernameOrEmail) {
        return userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng!"));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ProfileUpdateReqDto getProfileForEdit(Long id) {
        User u = findById(id);
        ProfileUpdateReqDto dto = new ProfileUpdateReqDto();
        dto.setFullName(u.getFullName());
        dto.setEmail(u.getEmail());
        dto.setPhone(u.getPhone());
        return dto;
    }

    /**
     * @param beforePasswordChangeCommit chỉ gọi khi lần cập nhật này đổi mật khẩu: nhận đúng giá trị sắp ghi vào
     *                                   {@code passwordChangedAt}, chạy TRƯỚC khi transaction commit — người gọi ghi mốc
     *                                   xác thực của session hiện tại vào kho session ở đây
     *                                   ({@code PasswordChangeSessionHandler#stampCurrentSession}), để request song song
     *                                   của chính session đó không bị huỷ. Ném exception → không đổi gì.
     * @return thời điểm đổi mật khẩu vừa ghi vào {@code passwordChangedAt} nếu lần cập nhật này có đổi mật khẩu; mọi
     *         session khác của tài khoản bị huỷ ({@code AccountStatusService}). Rỗng nếu không đổi mật khẩu.
     */
    @Transactional
    public Optional<Instant> updateProfile(Long id, ProfileUpdateReqDto dto,
                                           Consumer<Instant> beforePasswordChangeCommit) {
        User user = findById(id);

        if (userRepository.existsByEmailAndIdNot(dto.getEmail(), id)) {
            throw new DuplicateResourceException("Email đã được sử dụng bởi tài khoản khác!");
        }
        if (userRepository.existsByPhoneAndIdNot(dto.getPhone(), id)) {
            throw new DuplicateResourceException("Số điện thoại đã được sử dụng bởi tài khoản khác!");
        }

        boolean emailChanged = !Objects.equals(user.getEmail(), dto.getEmail());
        boolean passwordChanged = !blank(dto.getNewPassword());
        if (passwordChanged) {
            // Độ dài/mật khẩu bị lộ: @ValidPassword trên ProfileUpdateReqDto (PasswordPolicy), đã chạy ở controller
            if (blank(dto.getCurrentPassword())) {
                throw new IllegalArgumentException("Mật khẩu hiện tại không đúng!");
            }
            // Giới hạn số lần thử như đăng nhập: ai chiếm được session không được đoán mật khẩu hiện tại vô hạn lần
            loginAttemptService.beginPasswordChange(id);
            if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPassword())) {
                // Sai mật khẩu hiện tại lặp lại = dấu hiệu dò mật khẩu bằng session bị chiếm → ghi audit ngay
                auditLogService.passwordChangeFailed(id, user.getUsername(), "WRONG_CURRENT_PASSWORD");
                loginAttemptService.passwordChangeFailed(id, user.getUsername());
                throw new IllegalArgumentException("Mật khẩu hiện tại không đúng!");
            }
            loginAttemptService.passwordChangeSucceeded(id);
            if (!dto.getNewPassword().equals(dto.getConfirmNewPassword())) {
                throw new IllegalArgumentException("Mật khẩu xác nhận mới không khớp!");
            }
            user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
            // Lấy mốc SAU khi encode (BCrypt chậm): khoảng từ mốc tới commit càng ngắn càng tốt
            user.setPasswordChangedAt(authenticationClock.now());
            beforePasswordChangeCommit.accept(user.getPasswordChangedAt());
        }

        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        userRepository.save(user);
        log.info("User #{} updated own profile", id);

        if (passwordChanged) {
            auditLogService.passwordChanged(id, user.getUsername());
        }
        if (emailChanged) {
            auditLogService.userEmailChanged(id, user.getUsername());
        }
        return passwordChanged ? Optional.of(user.getPasswordChangedAt()) : Optional.empty();
    }

    // -------------------------------------------------------
    // Admin: Search
    // -------------------------------------------------------

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<UserResDto> searchUsers(String keyword, String roleStr,
                                        String isActiveStr, int page, int size) {
        Role role = parseRole(roleStr);
        Boolean isActive = parseBoolean(isActiveStr);
        String kw = blank(keyword) ? null : keyword.trim();

        return userRepository.search(kw, role, isActive, PageRequest.of(Math.max(page, 0), size))
                .map(UserResDto::from);
    }

    // -------------------------------------------------------
    // Admin: Get for edit
    // -------------------------------------------------------

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserEditReqDto getUserForEdit(Long id) {
        User u = findById(id);
        UserEditReqDto dto = new UserEditReqDto();
        dto.setId(u.getId());
        dto.setFullName(u.getFullName());
        dto.setEmail(u.getEmail());
        dto.setPhone(u.getPhone());
        dto.setRole(u.getRole());
        dto.setIsActive(u.getIsActive());
        return dto;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public UserResDto getUserDetail(Long id) {
        return UserResDto.from(findById(id));
    }

    // -------------------------------------------------------
    // Admin: Update
    // -------------------------------------------------------

    @Transactional
    public void updateUser(Long id, UserEditReqDto dto) {
        User user = findById(id);

        if (userRepository.existsByEmailAndIdNot(dto.getEmail(), id)) {
            throw new DuplicateResourceException("Email đã được sử dụng bởi tài khoản khác!");
        }
        if (userRepository.existsByPhoneAndIdNot(dto.getPhone(), id)) {
            throw new DuplicateResourceException("Số điện thoại đã được sử dụng bởi tài khoản khác!");
        }

        Role oldRole = user.getRole();
        boolean emailChanged = !Objects.equals(user.getEmail(), dto.getEmail());
        boolean wasActive = Boolean.TRUE.equals(user.getIsActive());
        boolean active = Boolean.TRUE.equals(dto.getIsActive());
        boolean passwordReset = dto.getNewPassword() != null && !dto.getNewPassword().isBlank();

        user.setFullName(dto.getFullName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRole(dto.getRole());
        user.setIsActive(active);

        if (passwordReset) {
            user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
            // Admin đặt lại mật khẩu: MỌI session của tài khoản này (đều xác thực trước mốc) bị huỷ ở request kế tiếp —
            // kể cả session của chính admin nếu họ đặt lại mật khẩu của mình ở đây
            user.setPasswordChangedAt(authenticationClock.now());
        }

        userRepository.save(user);
        log.info("Admin updated user #{}: {}", id, user.getUsername());

        if (oldRole != dto.getRole()) {
            auditLogService.userRoleChanged(id, user.getUsername(), oldRole, dto.getRole());
        }
        if (wasActive != active) {
            auditLogService.userActiveChanged(id, user.getUsername(), active);
        }
        if (passwordReset) {
            auditLogService.userPasswordReset(id, user.getUsername());
        }
        if (emailChanged) {
            auditLogService.userEmailChanged(id, user.getUsername());
        }
    }

    // -------------------------------------------------------
    // Admin: Toggle active status (quick action from list)
    // -------------------------------------------------------

    @Transactional
    public void toggleActive(Long id) {
        User user = findById(id);
        boolean active = !Boolean.TRUE.equals(user.getIsActive());
        user.setIsActive(active);
        userRepository.save(user);
        auditLogService.userActiveChanged(id, user.getUsername(), active);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy user #" + id));
    }

    private static Role parseRole(String value) {
        if (blank(value)) return null;
        try { return Role.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Boolean parseBoolean(String value) {
        if (blank(value)) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1"  -> Boolean.TRUE;
            case "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
