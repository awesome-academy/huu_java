package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;

import com.huuhv.foodsndrinks.repository.projection.UsernameAccountStatus;

import java.time.Instant;
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

    private static final Instant CHANGED_AT = Instant.parse("2026-11-28T03:00:00.123Z");

    private record Status(Boolean getActive, Role getRole, Instant getPasswordChangedAt) implements UserAccountStatus {
        /** Tài khoản chưa từng đổi mật khẩu. */
        Status(Boolean active, Role role) {
            this(active, role, null);
        }
    }

    private void givenAccount(Boolean active, Role role) {
        when(userRepository.findAccountStatusByUsername(USERNAME)).thenReturn(Optional.of(new Status(active, role)));
    }

    private void givenPasswordChangedAt(Instant changedAt) {
        when(userRepository.findAccountStatusByUsername(USERNAME))
                .thenReturn(Optional.of(new Status(true, Role.ROLE_USER, changedAt)));
    }

    private Optional<String> reasonForSessionAuthenticatedAt(Instant authenticatedAt) {
        return service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER"),
                authenticatedAt);
    }

    @Test
    void activeAccount_withSameRole_keepsSession() {
        givenAccount(true, Role.ROLE_USER);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER"), null))
                .isEmpty();
    }

    @Test
    void nonRoleAuthorities_areIgnored() {
        givenAccount(true, Role.ROLE_ADMIN);

        // vd. factor authority Spring Security tự thêm khi đăng nhập
        assertThat(service.sessionRevocationReason(USERNAME,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN", "FACTOR_PASSWORD"), null)).isEmpty();
    }

    @Test
    void lockedAccount_revokesSession() {
        givenAccount(false, Role.ROLE_USER);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER"), null))
                .contains(AccountStatusService.DISABLED);
    }

    @Test
    void demotedAdmin_revokesSession() {
        givenAccount(true, Role.ROLE_USER);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_ADMIN"), null))
                .contains(AccountStatusService.ROLE_CHANGED);
    }

    @Test
    void promotedUser_revokesSession_soNewRoleNeedsFreshLogin() {
        givenAccount(true, Role.ROLE_ADMIN);

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER"), null))
                .contains(AccountStatusService.ROLE_CHANGED);
    }

    @Test
    void deletedOrRenamedAccount_revokesSession() {
        when(userRepository.findAccountStatusByUsername(USERNAME)).thenReturn(Optional.empty());

        assertThat(service.sessionRevocationReason(USERNAME, AuthorityUtils.createAuthorityList("ROLE_USER"), null))
                .contains(AccountStatusService.ACCOUNT_NOT_FOUND);
    }

    // -------------------------------------------------------
    // Đổi mật khẩu (issue #37)
    // -------------------------------------------------------

    @Test
    void sessionAuthenticatedBeforePasswordChange_isRevoked() {
        givenPasswordChangedAt(CHANGED_AT);

        assertThat(reasonForSessionAuthenticatedAt(CHANGED_AT.minusMillis(1)))
                .contains(AccountStatusService.PASSWORD_CHANGED);
        assertThat(reasonForSessionAuthenticatedAt(CHANGED_AT.minusSeconds(3600)))
                .contains(AccountStatusService.PASSWORD_CHANGED);
    }

    @Test
    void sessionAuthenticatedAtOrAfterPasswordChange_isKept() {
        givenPasswordChangedAt(CHANGED_AT);

        // Bằng đúng mốc: session vừa tự đổi mật khẩu được ghi lại mốc = passwordChangedAt
        assertThat(reasonForSessionAuthenticatedAt(CHANGED_AT)).isEmpty();
        // Sau mốc: đăng nhập lại bằng mật khẩu mới
        assertThat(reasonForSessionAuthenticatedAt(CHANGED_AT.plusMillis(1))).isEmpty();
    }

    @Test
    void sessionWithoutAuthenticationTime_isRevoked_onceThePasswordHasEverChanged() {
        givenPasswordChangedAt(CHANGED_AT);

        // Fail-closed: không biết session đăng nhập lúc nào thì coi như cũ
        assertThat(reasonForSessionAuthenticatedAt(null)).contains(AccountStatusService.PASSWORD_CHANGED);
    }

    @Test
    void accountThatNeverChangedPassword_keepsEverySession_withOrWithoutAuthenticationTime() {
        givenPasswordChangedAt(null);   // kể cả tài khoản chỉ đăng nhập OAuth2 (không có mật khẩu)

        assertThat(reasonForSessionAuthenticatedAt(null)).isEmpty();
        assertThat(reasonForSessionAuthenticatedAt(CHANGED_AT.minusSeconds(3600))).isEmpty();
    }

    @Test
    void lockedOrDemotedAccount_isReportedBeforePasswordChange() {
        when(userRepository.findAccountStatusByUsername(USERNAME))
                .thenReturn(Optional.of(new Status(false, Role.ROLE_USER, CHANGED_AT)));

        assertThat(reasonForSessionAuthenticatedAt(CHANGED_AT.minusSeconds(1))).contains(AccountStatusService.DISABLED);
    }

    // -------------------------------------------------------
    // Tra nhiều tài khoản (thu hồi kết nối WebSocket)
    // -------------------------------------------------------

    private record NamedStatus(String getUsername, Boolean getActive, Role getRole) implements UsernameAccountStatus {
        @Override
        public Instant getPasswordChangedAt() {
            return null;
        }
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
        assertThat(AccountStatusService.revocationReason(null, AuthorityUtils.createAuthorityList("ROLE_USER"), null))
                .contains(AccountStatusService.ACCOUNT_NOT_FOUND);
        assertThat(AccountStatusService.revocationReason(new Status(false, Role.ROLE_USER),
                AuthorityUtils.createAuthorityList("ROLE_USER"), null)).contains(AccountStatusService.DISABLED);
        assertThat(AccountStatusService.revocationReason(new Status(true, Role.ROLE_USER),
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"), null)).contains(AccountStatusService.ROLE_CHANGED);
        assertThat(AccountStatusService.revocationReason(new Status(true, Role.ROLE_USER),
                AuthorityUtils.createAuthorityList("ROLE_USER"), null)).isEmpty();
        // Kết nối WebSocket mở từ session đăng nhập trước khi mật khẩu đổi
        assertThat(AccountStatusService.revocationReason(new Status(true, Role.ROLE_USER, CHANGED_AT),
                AuthorityUtils.createAuthorityList("ROLE_USER"), CHANGED_AT.minusSeconds(1)))
                .contains(AccountStatusService.PASSWORD_CHANGED);
    }
}
