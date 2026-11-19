package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.config.LoginAttemptProperties;
import com.huuhv.foodsndrinks.security.LoginThrottledException;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    private static final LoginAttemptProperties PROPS =
            new LoginAttemptProperties(3, Duration.ofMinutes(15), Duration.ofMinutes(10));

    @Mock private AuditLogService auditLogService;

    private final AtomicLong nanos = new AtomicLong();
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(PROPS, auditLogService, nanos::get);
    }

    private void fail(String loginId, int times) {
        for (int i = 0; i < times; i++) {
            service.loginFailed(loginId);
        }
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void reachingMaxFailures_blocksAndAuditsOnce() {
        fail("alice", 2);
        assertThatCode(() -> service.checkNotBlocked("alice")).doesNotThrowAnyException();

        service.loginFailed("alice");

        assertThatThrownBy(() -> service.checkNotBlocked("alice"))
                .isInstanceOfSatisfying(LoginThrottledException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(600));
        verify(auditLogService).loginLockedOut("alice", 3, Duration.ofMinutes(10));
    }

    @Test
    void key_isCaseInsensitiveAndTrimmed() {
        fail("Alice", 1);
        fail(" alice ", 1);
        fail("ALICE", 1);

        assertThatThrownBy(() -> service.checkNotBlocked("alice")).isInstanceOf(LoginThrottledException.class);
    }

    @Test
    void lock_expiresAfterLockDuration_andRetryAfterCountsDown() {
        fail("bob", 3);
        advance(Duration.ofMinutes(4));
        assertThatThrownBy(() -> service.checkNotBlocked("bob"))
                .isInstanceOfSatisfying(LoginThrottledException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(360));

        advance(Duration.ofMinutes(6));

        assertThatCode(() -> service.checkNotBlocked("bob")).doesNotThrowAnyException();
    }

    @Test
    void window_isFixedFromFirstFailure_notSliding() {
        service.loginFailed("carol");
        advance(Duration.ofMinutes(10));
        service.loginFailed("carol");
        advance(Duration.ofMinutes(6)); // 16 phút sau lần sai đầu → cửa sổ đã hết, bộ đếm về 0
        service.loginFailed("carol");

        assertThatCode(() -> service.checkNotBlocked("carol")).doesNotThrowAnyException();
        verify(auditLogService, never()).loginLockedOut(anyString(), anyInt(), any());
    }

    @Test
    void success_resetsCounter() {
        fail("dave", 2);
        service.loginSucceeded("dave");
        fail("dave", 2);

        assertThatCode(() -> service.checkNotBlocked("dave")).doesNotThrowAnyException();
    }

    @Test
    void lockingOneName_doesNotAffectOthers() {
        fail("eve", 3);

        assertThatCode(() -> service.checkNotBlocked("frank")).doesNotThrowAnyException();
    }
}
