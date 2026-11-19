package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Quy tắc huỷ session web khi tài khoản đổi trạng thái sau khi đăng nhập — không khởi động Spring, không cần DB. */
class AccountStatusServiceTest {

    private static final String USERNAME = "alice";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountStatusService service = new AccountStatusService(userRepository);

    private record Status(Boolean getActive, Role getRole) implements UserAccountStatus {
    }

    private void givenAccount(Boolean active, Role role) {
        when(userRepository.findAccountStatusByUsername(USERNAME)).thenReturn(Optional.of(new Status(active, role)));
    }

    @Test
    void activeAccount_withSameRole_keepsSession() {
        givenAccount(true, Role.ROLE_USER);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER")))
                .isEmpty();
    }

    @Test
    void nonRoleAuthorities_areIgnored() {
        givenAccount(true, Role.ROLE_ADMIN);

        // vd. factor authority Spring Security tự thêm khi đăng nhập
        assertThat(service.sessionRevocationReason(USERNAME,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN", "FACTOR_PASSWORD"))).isEmpty();
    }

    @Test
    void lockedAccount_revokesSession() {
        givenAccount(false, Role.ROLE_USER);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER")))
                .contains(AccountStatusService.DISABLED);
    }

    @Test
    void demotedAdmin_revokesSession() {
        givenAccount(true, Role.ROLE_USER);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_ADMIN")))
                .contains(AccountStatusService.ROLE_CHANGED);
    }

    @Test
    void promotedUser_revokesSession_soNewRoleNeedsFreshLogin() {
        givenAccount(true, Role.ROLE_ADMIN);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER")))
                .contains(AccountStatusService.ROLE_CHANGED);
    }

    @Test
    void deletedOrRenamedAccount_revokesSession() {
        when(userRepository.findAccountStatusByUsername(USERNAME)).thenReturn(Optional.empty());

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER")))
                .contains(AccountStatusService.ACCOUNT_NOT_FOUND);
    }
}
