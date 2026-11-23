package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.config.properties.LoginAttemptProperties;
import com.huuhv.foodsndrinks.exception.PasswordChangeThrottledException;
import com.huuhv.foodsndrinks.exception.LoginThrottledException;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService.LoginAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    /** Cặp (IP, tên): 3 · IP: 6 · tài khoản: 8 · cửa sổ 15 phút · khoá 10 phút. */
    private static final LoginAttemptProperties PROPS =
            new LoginAttemptProperties(3, 6, 8, Duration.ofMinutes(15), Duration.ofMinutes(10));
    private static final String IP = "203.0.113.7";

    @Mock private AuditLogService auditLogService;

    private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(PROPS,
                InMemoryAttemptLimitRepository.factory(() -> Instant.ofEpochMilli(millis.get())), auditLogService);
    }

    private void fail(String loginId, String ip, int times) {
        for (int i = 0; i < times; i++) {
            service.failed(service.begin(loginId, ip));
        }
    }

    private void succeed(String loginId, String ip) {
        service.succeeded(service.begin(loginId, ip));
    }

    private void advance(Duration duration) {
        millis.addAndGet(duration.toMillis());
    }

    private void assertBlocked(String loginId, String ip) {
        assertThatThrownBy(() -> service.begin(loginId, ip)).isInstanceOf(LoginThrottledException.class);
    }

    private void assertAllowed(String loginId, String ip) {
        assertThatCode(() -> service.abandoned(service.begin(loginId, ip))).doesNotThrowAnyException();
    }

    // -------------------------------------------------------
    // Cặp (IP, tên đăng nhập)
    // -------------------------------------------------------

    @Test
    void reachingMaxFailures_locksTheIpUserPair_andAuditsOnce() {
        fail("alice", IP, 2);
        assertAllowed("alice", IP);

        fail("alice", IP, 1);

        assertThatThrownBy(() -> service.begin("alice", IP))
                .isInstanceOfSatisfying(LoginThrottledException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(600));
        verify(auditLogService).loginLockedOut("alice", LoginAttemptService.SCOPE_IP_USER, 3, Duration.ofMinutes(10));
    }

    @Test
    void lockedPair_doesNotLockTheSameNameFromAnotherIp() {
        fail("alice", IP, 3);

        assertBlocked("alice", IP);
        assertAllowed("alice", "198.51.100.1");
    }

    @Test
    void lock_expiresAfterLockDuration_andRetryAfterCountsDown() {
        fail("bob", IP, 3);
        advance(Duration.ofMinutes(4));
        assertThatThrownBy(() -> service.begin("bob", IP))
                .isInstanceOfSatisfying(LoginThrottledException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(360));

        advance(Duration.ofMinutes(6));

        assertAllowed("bob", IP);
    }

    @Test
    void window_isFixedFromFirstAttempt_notSliding() {
        fail("carol", IP, 1);
        advance(Duration.ofMinutes(10));
        fail("carol", IP, 1);
        advance(Duration.ofMinutes(6)); // 16 phút sau lần đầu → cửa sổ đã hết, bộ đếm về 0
        fail("carol", IP, 1);

        assertAllowed("carol", IP);
        verify(auditLogService, never()).loginLockedOut(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void success_resetsCounter() {
        fail("dave", IP, 2);
        succeed("dave", IP);
        fail("dave", IP, 2);

        assertAllowed("dave", IP);
    }

    // -------------------------------------------------------
    // #1 Chuẩn hoá tên đăng nhập như collation MySQL
    // -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ádmin", "ÀDMIN", " admin ", "ａｄｍｉｎ", "ádmin"})
    void unicodeVariants_shareTheCounterOfTheBaseName(String variant) {
        fail("admin", IP, 2);
        fail(variant, IP, 1);

        assertBlocked("admin", IP);
    }

    @Test
    void canonicalAccount_isCountedAcrossUsernameAndEmail() {
        // 8 lần sai trên tài khoản "erin", xen kẽ username/email và từ nhiều IP (không cặp nào chạm ngưỡng 3)
        for (int i = 0; i < 8; i++) {
            String loginId = i % 2 == 0 ? "erin" : "erin@example.com";
            LoginAttempt attempt = service.begin(loginId, "198.51.100." + i);
            service.addAccount(attempt, "erin");
            service.failed(attempt);
        }

        LoginAttempt viaEmail = service.begin("erin@example.com", "192.0.2.99");
        assertThatThrownBy(() -> service.addAccount(viaEmail, "erin")).isInstanceOf(LoginThrottledException.class);
        verify(auditLogService).loginLockedOut("erin", LoginAttemptService.SCOPE_ACCOUNT, 8, Duration.ofMinutes(10));
    }

    // -------------------------------------------------------
    // #3 Theo IP (password spraying) và theo tài khoản (phân tán)
    // -------------------------------------------------------

    @Test
    void passwordSpraying_fromOneIp_isBlockedAcrossUsernames() {
        for (int i = 0; i < 6; i++) {
            fail("user" + i, IP, 1);
        }

        assertBlocked("someone-new", IP);
        assertAllowed("someone-new", "198.51.100.1");
        verify(auditLogService).loginSourceBlocked(6, Duration.ofMinutes(10));
    }

    @Test
    void successfulLogin_doesNotResetTheIpCounter() {
        for (int i = 0; i < 5; i++) {
            fail("victim" + i, IP, 1);
            succeed("attacker-own-account", IP); // xen kẽ đăng nhập đúng vào tài khoản của chính mình
        }
        fail("victim5", IP, 1);

        assertBlocked("victim6", IP);
    }

    @Test
    void distributedAttack_locksTheAccount_butSingleIpCannot() {
        // Một IP chỉ khoá được cặp (IP, admin) → admin vẫn đăng nhập được từ IP khác
        fail("admin", IP, 3);
        assertAllowed("admin", "192.0.2.1");

        for (int i = 0; i < 5; i++) {
            fail("admin", "198.51.100." + i, 1);
        }

        assertBlocked("admin", "192.0.2.1");
        verify(auditLogService).loginLockedOut("admin", LoginAttemptService.SCOPE_ACCOUNT, 8, Duration.ofMinutes(10));
    }

    @Test
    void ipv6Addresses_inTheSame64_shareTheIpCounter() {
        for (int i = 0; i < 6; i++) {
            fail("user" + i, "2001:db8:1:2::" + Integer.toHexString(i + 1), 1);
        }

        assertBlocked("other", "2001:db8:1:2:ffff::1");
        assertAllowed("other", "2001:db8:1:3::1");
    }

    // -------------------------------------------------------
    // #2 Giữ chỗ trước khi so mật khẩu
    // -------------------------------------------------------

    @Test
    void concurrentAttempts_beyondMax_areRejectedBeforePasswordCheck() {
        List<LoginAttempt> inFlight = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inFlight.add(service.begin("frank", IP)); // 3 request đang so mật khẩu, chưa có kết quả
        }

        assertThatThrownBy(() -> service.begin("frank", IP))
                .isInstanceOfSatisfying(LoginThrottledException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));

        inFlight.forEach(service::failed);
        assertThatThrownBy(() -> service.begin("frank", IP))
                .isInstanceOfSatisfying(LoginThrottledException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(600));
    }

    @Test
    void abandonedAttempt_releasesItsSlot() {
        for (int i = 0; i < 10; i++) {
            service.abandoned(service.begin("grace", IP)); // vd. tài khoản bị khoá: không tính là sai
        }

        assertAllowed("grace", IP);
        verify(auditLogService, never()).loginLockedOut(anyString(), anyString(), anyInt(), any());
    }

    // -------------------------------------------------------
    // #5 Đổi mật khẩu
    // -------------------------------------------------------

    @Test
    void passwordChange_wrongCurrentPasswordMaxTimes_throwsAndAudits() {
        for (int i = 0; i < 2; i++) {
            service.beginPasswordChange(42L);
            service.passwordChangeFailed(42L, "heidi");
        }
        service.beginPasswordChange(42L);

        assertThatThrownBy(() -> service.passwordChangeFailed(42L, "heidi"))
                .isInstanceOf(PasswordChangeThrottledException.class);
        assertThatThrownBy(() -> service.beginPasswordChange(42L))
                .isInstanceOf(PasswordChangeThrottledException.class);
        verify(auditLogService).passwordChangeLockedOut(42L, "heidi", 3, Duration.ofMinutes(10));
    }

    @Test
    void passwordChange_successResetsCounter() {
        for (int i = 0; i < 2; i++) {
            service.beginPasswordChange(7L);
            service.passwordChangeFailed(7L, "ivan");
        }
        service.beginPasswordChange(7L);
        service.passwordChangeSucceeded(7L);

        for (int i = 0; i < 2; i++) {
            service.beginPasswordChange(7L);
            service.passwordChangeFailed(7L, "ivan");
        }
        assertThatCode(() -> service.beginPasswordChange(7L)).doesNotThrowAnyException();
    }

    @Test
    void normalize_matchesMysqlAccentAndCaseInsensitiveComparison() {
        assertThat(LoginAttemptService.normalize("  Nguyễn.Văn@Example.COM ")).isEqualTo("nguyen.van@example.com");
        assertThat(LoginAttemptService.normalize("ＡＤＭＩＮ")).isEqualTo("admin");
        assertThat(LoginAttemptService.normalize(null)).isEmpty();
    }
}
