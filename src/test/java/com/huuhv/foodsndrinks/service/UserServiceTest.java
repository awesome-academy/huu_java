package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.dto.request.RegisterReqDto;
import com.huuhv.foodsndrinks.dto.request.UserEditReqDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test {@link UserService}: đăng ký, tạo tài khoản OAuth2 lần đầu, khoá/mở khoá và admin sửa user. Các nhánh chống
 * chiếm tài khoản khi liên kết theo email nằm ở {@link UserServiceSecurityTest}. Kiểm tra trùng username/email khi đăng
 * ký nằm ở {@code AuthController} (test bằng {@code AuthControllerTest}).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditLogService auditLogService;
    @Mock private LoginAttemptService loginAttemptService;

    @InjectMocks private UserService userService;

    private static User existing(Long id, String username, boolean active) {
        return User.builder().id(id).username(username).email(username + "@example.com").password("hash")
                .role(Role.ROLE_USER).isActive(active).build();
    }

    // -------------------------------------------------------
    // Đăng ký
    // -------------------------------------------------------

    @Nested
    class Register {

        @Test
        void storesHashedPassword_asActiveLocalUser() {
            RegisterReqDto dto = new RegisterReqDto();
            dto.setFullName("Alice");
            dto.setUsername("alice");
            dto.setEmail("alice@example.com");
            dto.setPhone("0912345678");
            dto.setPassword("correct-horse-battery");
            dto.setConfirmPassword("correct-horse-battery");
            when(passwordEncoder.encode("correct-horse-battery")).thenReturn("bcrypt-hash");

            userService.registerNewUser(dto);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            User user = saved.getValue();
            assertThat(user.getUsername()).isEqualTo("alice");
            assertThat(user.getPassword()).isEqualTo("bcrypt-hash");
            assertThat(user.getRole()).isEqualTo(Role.ROLE_USER);
            assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
            assertThat(user.getIsActive()).isTrue();
        }

        @Test
        void duplicateChecks_delegateToRepository() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

            assertThat(userService.existsByUsername("alice")).isTrue();
            assertThat(userService.existsByEmail("new@example.com")).isFalse();
        }
    }

    // -------------------------------------------------------
    // OAuth2: tìm hoặc tạo user local
    // -------------------------------------------------------

    @Nested
    class ProvisionOAuthUser {

        @Test
        void knownProviderIdentity_returnsLinkedUser_withoutLookingUpEmail() {
            User linked = existing(1L, "alice", true);
            when(userRepository.findByAuthProviderAndProviderId(AuthProvider.GOOGLE, "g-1"))
                    .thenReturn(Optional.of(linked));

            assertThat(userService.provisionOAuthUser(AuthProvider.GOOGLE, "g-1", "alice@example.com", true,
                    "Alice", null)).isSameAs(linked);
            verify(userRepository, never()).findByEmail(anyString());
            verify(userRepository, never()).save(any());
        }

        @Test
        void newIdentity_createsUser_withUniqueSlugUsername() {
            when(userRepository.findByAuthProviderAndProviderId(AuthProvider.FACEBOOK, "fb-9"))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail("an@example.com")).thenReturn(Optional.empty());
            when(userRepository.existsByUsername("nguyen-van-an")).thenReturn(true);
            when(userRepository.existsByUsername("nguyen-van-an-2")).thenReturn(false);

            User created = userService.provisionOAuthUser(AuthProvider.FACEBOOK, "fb-9", "an@example.com", true,
                    "Nguyễn Văn An", "https://platform-lookaside.fbsbx.com/a.jpg");

            verify(userRepository).save(created);
            assertThat(created.getUsername()).isEqualTo("nguyen-van-an-2");
            assertThat(created.getEmail()).isEqualTo("an@example.com");
            assertThat(created.getPassword()).isNull();
            assertThat(created.getRole()).isEqualTo(Role.ROLE_USER);
            assertThat(created.getAuthProvider()).isEqualTo(AuthProvider.FACEBOOK);
            assertThat(created.getProviderId()).isEqualTo("fb-9");
            assertThat(created.getIsActive()).isTrue();
        }

        @Test
        void providerWithoutEmailOrName_getsPlaceholders() {
            when(userRepository.findByAuthProviderAndProviderId(AuthProvider.TWITTER, "tw-7"))
                    .thenReturn(Optional.empty());

            User created = userService.provisionOAuthUser(AuthProvider.TWITTER, "tw-7", null, false, "  ", null);

            assertThat(created.getUsername()).isEqualTo("twitter-tw-7");
            assertThat(created.getEmail()).isEqualTo("twitter-tw-7@no-email.fnbstore.local");
            assertThat(created.getFullName()).isEqualTo("Người dùng TWITTER");
            verify(userRepository, never()).findByEmail(anyString());
        }
    }

    // -------------------------------------------------------
    // Admin: khoá/mở khoá, sửa user
    // -------------------------------------------------------

    @Nested
    class ToggleActive {

        @Test
        void activeUser_isDisabled_andAudited() {
            User user = existing(5L, "bob", true);
            when(userRepository.findById(5L)).thenReturn(Optional.of(user));

            userService.toggleActive(5L);

            assertThat(user.getIsActive()).isFalse();
            verify(userRepository).save(user);
            verify(auditLogService).userActiveChanged(5L, "bob", false);
        }

        @Test
        void disabledUser_isEnabled_andAudited() {
            User user = existing(5L, "bob", false);
            when(userRepository.findById(5L)).thenReturn(Optional.of(user));

            userService.toggleActive(5L);

            assertThat(user.getIsActive()).isTrue();
            verify(auditLogService).userActiveChanged(5L, "bob", true);
        }

        @Test
        void unknownUser_throwsNotFound() {
            when(userRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.toggleActive(404L)).isInstanceOf(ResourceNotFoundException.class);
            verify(userRepository, never()).save(any());
            verifyNoInteractions(auditLogService);
        }
    }

    @Nested
    class AdminUpdate {

        private UserEditReqDto edit(String email, Role role, boolean active) {
            UserEditReqDto dto = new UserEditReqDto();
            dto.setId(5L);
            dto.setFullName("Bob");
            dto.setEmail(email);
            dto.setPhone("0912345678");
            dto.setRole(role);
            dto.setIsActive(active);
            return dto;
        }

        @Test
        void emailOfAnotherUser_isRejected() {
            when(userRepository.findById(5L)).thenReturn(Optional.of(existing(5L, "bob", true)));
            when(userRepository.existsByEmailAndIdNot("alice@example.com", 5L)).thenReturn(true);

            assertThatThrownBy(() -> userService.updateUser(5L, edit("alice@example.com", Role.ROLE_USER, true)))
                    .isInstanceOf(DuplicateResourceException.class);
            verify(userRepository, never()).save(any());
            verifyNoInteractions(auditLogService);
        }

        @Test
        void promoteToAdmin_isAudited_withoutPasswordReset() {
            User user = existing(5L, "bob", true);
            when(userRepository.findById(5L)).thenReturn(Optional.of(user));

            userService.updateUser(5L, edit("bob@example.com", Role.ROLE_ADMIN, true));

            assertThat(user.getRole()).isEqualTo(Role.ROLE_ADMIN);
            assertThat(user.getPassword()).isEqualTo("hash");
            verify(auditLogService).userRoleChanged(5L, "bob", Role.ROLE_USER, Role.ROLE_ADMIN);
            verify(auditLogService, never()).userPasswordReset(any(), any());
            verify(auditLogService, never()).userActiveChanged(any(), any(), anyBoolean());
            verifyNoInteractions(passwordEncoder);
        }
    }
}
