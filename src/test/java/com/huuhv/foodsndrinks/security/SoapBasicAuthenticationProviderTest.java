package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.exception.LoginThrottledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SoapBasicAuthenticationProvider}: request song song cùng thông tin đăng nhập chỉ xác thực (giữ chỗ trong bộ đếm,
 * so BCrypt) một lần; lần đúng được nhớ ngắn hạn nhưng trạng thái tài khoản vẫn kiểm tra ở mọi request.
 */
class SoapBasicAuthenticationProviderTest {

    private static final String HASH = "{bcrypt}hash-of-secret";

    private final AuthenticationProvider delegate = mock(AuthenticationProvider.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SoapBasicAuthenticationProvider provider =
            new SoapBasicAuthenticationProvider(delegate, userDetailsService, nanos::get);
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentRequestsWithTheSameCredentials_areVerifiedOnce_andAllSucceed() throws Exception {
        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.authenticate(any())).thenAnswer(invocation -> {
            delegateEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return authenticated(user(HASH, true));
        });
        when(userDetailsService.loadUserByUsername("partner")).thenReturn(user(HASH, true));

        List<Future<Authentication>> results = new ArrayList<>();
        results.add(pool.submit(() -> provider.authenticate(token("partner", "secret"))));
        assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 7; i++) {
            results.add(pool.submit(() -> provider.authenticate(token("partner", "secret"))));
        }
        release.countDown();

        for (Future<Authentication> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS).isAuthenticated()).isTrue();
        }
        // Một lần giữ chỗ + BCrypt cho cả 8 request: request thứ 6 trở đi không còn bị LoginThrottledException
        verify(delegate, times(1)).authenticate(any());
    }

    @Test
    void wrongPassword_isNotRemembered_andConcurrentDuplicatesGetTheSameFailure() throws Exception {
        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.authenticate(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                delegateEntered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            throw new BadCredentialsException("Bad credentials");
        });

        Future<?> first = pool.submit(() -> provider.authenticate(token("partner", "guess")));
        assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> duplicate = pool.submit(() -> provider.authenticate(token("partner", "guess")));
        release.countDown();

        for (Future<?> result : List.of(first, duplicate)) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BadCredentialsException.class);
        }
        // Lần sau với cùng cặp sai vẫn đi qua giới hạn đăng nhập sai (không nhớ lần thất bại)
        assertThatThrownBy(() -> provider.authenticate(token("partner", "guess")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void throttledFirstRequest_isNotRemembered() {
        when(delegate.authenticate(any())).thenThrow(new LoginThrottledException(60))
                .thenReturn(authenticated(user(HASH, true)));

        assertThatThrownBy(() -> provider.authenticate(token("partner", "secret")))
                .isInstanceOf(LoginThrottledException.class);
        assertThat(provider.authenticate(token("partner", "secret")).isAuthenticated()).isTrue();
        verify(delegate, times(2)).authenticate(any());
    }

    @Test
    void rememberedCredentials_skipVerification_butTheAccountIsReloadedEveryTime() {
        when(delegate.authenticate(any())).thenReturn(authenticated(user(HASH, true)));
        when(userDetailsService.loadUserByUsername("partner")).thenReturn(user(HASH, true));

        provider.authenticate(token("partner", "secret"));
        Authentication second = provider.authenticate(token("partner", "secret"));

        assertThat(second.isAuthenticated()).isTrue();
        assertThat(second.getName()).isEqualTo("partner");
        assertThat(second.getCredentials()).isNull();
        verify(delegate, times(1)).authenticate(any());
        verify(userDetailsService, times(1)).loadUserByUsername("partner");
    }

    @Test
    void differentPassword_isNeverServedFromTheRememberedResult() {
        when(delegate.authenticate(any())).thenReturn(authenticated(user(HASH, true)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        provider.authenticate(token("partner", "secret"));

        assertThatThrownBy(() -> provider.authenticate(token("partner", "secret2")))
                .isInstanceOf(BadCredentialsException.class);
        verify(delegate, times(2)).authenticate(any());
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void accountDisabledAfterVerification_isRejectedOnTheNextRequest() {
        when(delegate.authenticate(any())).thenReturn(authenticated(user(HASH, true)));
        when(userDetailsService.loadUserByUsername("partner")).thenReturn(user(HASH, false));

        provider.authenticate(token("partner", "secret"));

        assertThatThrownBy(() -> provider.authenticate(token("partner", "secret")))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void accountDeletedAfterVerification_isBadCredentials() {
        when(delegate.authenticate(any())).thenReturn(authenticated(user(HASH, true)));
        when(userDetailsService.loadUserByUsername("partner")).thenThrow(new UsernameNotFoundException("gone"));

        provider.authenticate(token("partner", "secret"));

        assertThatThrownBy(() -> provider.authenticate(token("partner", "secret")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void passwordChangedAfterVerification_dropsTheRememberedResult_andVerifiesAgain() {
        when(delegate.authenticate(any())).thenReturn(authenticated(user(HASH, true)))
                .thenThrow(new BadCredentialsException("Bad credentials"));
        when(userDetailsService.loadUserByUsername("partner")).thenReturn(user("{bcrypt}new-hash", true));

        provider.authenticate(token("partner", "secret"));

        // Mật khẩu cũ: hash trong DB đã khác → so lại qua delegate (giới hạn đăng nhập sai) → sai
        assertThatThrownBy(() -> provider.authenticate(token("partner", "secret")))
                .isInstanceOf(BadCredentialsException.class);
        verify(delegate, times(2)).authenticate(any());
    }

    @Test
    void rememberedResult_expiresAfterTheTtl() {
        when(delegate.authenticate(any())).thenReturn(authenticated(user(HASH, true)));
        when(userDetailsService.loadUserByUsername("partner")).thenReturn(user(HASH, true));

        provider.authenticate(token("partner", "secret"));
        nanos.addAndGet(SoapBasicAuthenticationProvider.CACHE_TTL.plusSeconds(1).toNanos());
        provider.authenticate(token("partner", "secret"));

        verify(delegate, times(2)).authenticate(any());
    }

    private static UsernamePasswordAuthenticationToken token(String username, String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(username, password);
    }

    private static UserDetails user(String passwordHash, boolean enabled) {
        return User.withUsername("partner").password(passwordHash).roles("USER").disabled(!enabled).build();
    }

    private static Authentication authenticated(UserDetails user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }
}
