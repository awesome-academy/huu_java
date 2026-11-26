package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;

import com.huuhv.foodsndrinks.repository.projection.UsernameAccountStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    // -------------------------------------------------------
    // Tra nhiều tài khoản (thu hồi kết nối WebSocket)
    // -------------------------------------------------------

    private record NamedStatus(String getUsername, Boolean getActive, Role getRole) implements UsernameAccountStatus {
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAccountStatuses_deduplicates_andQueriesInBatches() {
        List<String> usernames = new ArrayList<>();
        for (int i = 0; i < AccountStatusService.BATCH_SIZE + 1; i++) {
            usernames.add("user" + i);
        }
        usernames.add("user0"); // trùng: nhiều tab của cùng một người
        when(userRepository.findAccountStatusesByUsernameIn(anyCollection())).thenAnswer(invocation ->
                ((Collection<String>) invocation.getArgument(0)).stream()
                        .filter(name -> !name.equals("user7"))   // tài khoản đã bị xoá
                        .map(name -> new NamedStatus(name, true, Role.ROLE_USER))
                        .toList());

        Map<String, UserAccountStatus> statuses = service.findAccountStatuses(usernames);

        assertThat(statuses).hasSize(AccountStatusService.BATCH_SIZE).doesNotContainKey("user7");
        verify(userRepository, times(2)).findAccountStatusesByUsernameIn(anyCollection());
    }

    @Test
    void revocationReason_sameRulesAsSessionCheck() {
        assertThat(AccountStatusService.revocationReason(null, AuthorityUtils.createAuthorityList("ROLE_USER")))
                .contains(AccountStatusService.ACCOUNT_NOT_FOUND);
        assertThat(AccountStatusService.revocationReason(new Status(false, Role.ROLE_USER),
                AuthorityUtils.createAuthorityList("ROLE_USER"))).contains(AccountStatusService.DISABLED);
        assertThat(AccountStatusService.revocationReason(new Status(true, Role.ROLE_USER),
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"))).contains(AccountStatusService.ROLE_CHANGED);
        assertThat(AccountStatusService.revocationReason(new Status(true, Role.ROLE_USER),
                AuthorityUtils.createAuthorityList("ROLE_USER"))).isEmpty();
    }
}
