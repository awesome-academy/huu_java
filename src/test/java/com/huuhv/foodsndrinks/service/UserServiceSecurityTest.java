package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.config.properties.LoginAttemptProperties;
import com.huuhv.foodsndrinks.dto.request.ProfileUpdateReqDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.OAuthEmailConflictException;
import com.huuhv.foodsndrinks.exception.PasswordChangeThrottledException;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.InMemoryAttemptLimitRepository;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Các nhánh bảo mật của {@link UserService}: liên kết OAuth2 theo email (#4) và đổi mật khẩu có giới hạn (#5). */
@ExtendWith(MockitoExtension.class)
class UserServiceSecurityTest {

    private static final String EMAIL = "victim@gmail.com";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditLogService auditLogService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        LoginAttemptService loginAttemptService = new LoginAttemptService(
                new LoginAttemptProperties(3, 20, 50, Duration.ofMinutes(15), Duration.ofMinutes(15)),
                InMemoryAttemptLimitRepository.factory(InstantSource.system()), auditLogService);
        userService = new UserService(userRepository, passwordEncoder, auditLogService, loginAttemptService);
        lenient().when(userRepository.findByAuthProviderAndProviderId(any(), anyString())).thenReturn(Optional.empty());
    }

    private static User existing(String password) {
        return User.builder().id(1L).username("victim").email(EMAIL).password(password)
                .role(Role.ROLE_USER).isActive(true).build();
    }

    // -------------------------------------------------------
    // #4 Pre-account takeover
    // -------------------------------------------------------

    @Test
    void verifiedEmail_ofLocalAccountWithPassword_isNotLinked() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing("$2a$10$attackerChosenHash")));

        assertThatThrownBy(() -> userService.provisionOAuthUser(AuthProvider.GOOGLE, "g-1", EMAIL, true, "Victim", null))
                .isInstanceOfSatisfying(OAuthEmailConflictException.class,
                        ex -> assertThat(ex.getExistingUsername()).isEqualTo("victim"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void unverifiedEmail_isNeverLinked_evenToOAuthOnlyAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing(null)));

        assertThatThrownBy(() -> userService.provisionOAuthUser(AuthProvider.GOOGLE, "g-1", EMAIL, false, "Victim", null))
                .isInstanceOf(OAuthEmailConflictException.class);
    }

    @Test
    void verifiedEmail_ofOAuthOnlyAccount_isLinked() {
        User oauthOnly = existing(null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(oauthOnly));

        assertThat(userService.provisionOAuthUser(AuthProvider.FACEBOOK, "fb-1", EMAIL, true, "Victim", null))
                .isSameAs(oauthOnly);
    }

    @Test
    void unverifiedEmail_isNotStoredOnNewAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        User created = userService.provisionOAuthUser(AuthProvider.GOOGLE, "g-2", EMAIL, false, "New User", null);

        assertThat(created.getEmail()).isEqualTo("google-g-2@no-email.fnbstore.local");
    }

    @Test
    void verifiedEmail_isStoredOnNewAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        User created = userService.provisionOAuthUser(AuthProvider.GOOGLE, "g-3", EMAIL, true, "New User", null);

        assertThat(created.getEmail()).isEqualTo(EMAIL);
    }

    // -------------------------------------------------------
    // #5 Dò mật khẩu hiện tại khi đổi mật khẩu
    // -------------------------------------------------------

    private static ProfileUpdateReqDto passwordChange(String current) {
        return new ProfileUpdateReqDto("Victim", EMAIL, "0912345678", current, "new-password-123", "new-password-123");
    }

    @Test
    void wrongCurrentPassword_isLimited_thenThrottled() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing("hash")));
        when(passwordEncoder.matches(anyString(), any())).thenReturn(false);

        for (int i = 0; i < 2; i++) {
            assertThatIllegalArgumentException().isThrownBy(() -> userService.updateProfile(1L, passwordChange("guess")));
        }
        assertThatThrownBy(() -> userService.updateProfile(1L, passwordChange("guess")))
                .isInstanceOf(PasswordChangeThrottledException.class);
        // Đã khoá: không so mật khẩu nữa, kể cả khi đoán đúng
        assertThatThrownBy(() -> userService.updateProfile(1L, passwordChange("right")))
                .isInstanceOf(PasswordChangeThrottledException.class);

        verify(passwordEncoder, never()).matches("right", "hash");
        verify(auditLogService).passwordChangeLockedOut(1L, "victim", 3, Duration.ofMinutes(15));
    }

    @Test
    void correctCurrentPassword_changesPassword_andResetsCounter() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing("hash")));
        when(passwordEncoder.matches("guess", "hash")).thenReturn(false);
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");

        for (int i = 0; i < 2; i++) {
            assertThatIllegalArgumentException().isThrownBy(() -> userService.updateProfile(1L, passwordChange("guess")));
        }
        userService.updateProfile(1L, passwordChange("right"));
        for (int i = 0; i < 2; i++) {
            assertThatIllegalArgumentException().isThrownBy(() -> userService.updateProfile(1L, passwordChange("guess")));
        }

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("new-hash");
    }
}
