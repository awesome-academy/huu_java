package com.huuhv.foodsndrinks.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    private final CompromisedPasswordChecker checker = mock(CompromisedPasswordChecker.class);

    @SuppressWarnings("unchecked")
    private static PasswordPolicy policy(CompromisedPasswordChecker checker) {
        ObjectProvider<CompromisedPasswordChecker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(checker);
        return new PasswordPolicy(provider);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullOrEmpty_isValid_meaningNoChange(String password) {
        assertThat(policy(checker).violation(password)).isEmpty();
    }

    @Test
    void lengthBoundaries_8to64() {
        PasswordPolicy policy = policy(null);

        assertThat(policy.violation("a".repeat(7))).contains(PasswordPolicy.LENGTH_MESSAGE);
        assertThat(policy.violation("a".repeat(8))).isEmpty();
        assertThat(policy.violation("a".repeat(64))).isEmpty();
        assertThat(policy.violation("a".repeat(65))).contains(PasswordPolicy.LENGTH_MESSAGE);
    }

    @Test
    void specialAndUnicodeCharacters_areAllowed_andCountedAsCharacters() {
        PasswordPolicy policy = policy(null);

        assertThat(policy.violation("mật khẩu 🔒 !@#")).isEmpty();
        // 8 emoji = 8 ký tự (16 char UTF-16) → hợp lệ
        assertThat(policy.violation("🔒".repeat(8))).isEmpty();
    }

    @Test
    void moreThan72Utf8Bytes_isRejected_becauseBcryptTruncates() {
        // 30 ký tự "ễ" (3 byte) = 90 byte, dưới 64 ký tự nhưng vượt giới hạn của BCrypt
        assertThat(policy(null).violation("ễ".repeat(30))).contains(PasswordPolicy.BYTES_MESSAGE);
    }

    @Test
    void compromisedPassword_isRejected() {
        when(checker.check("password123")).thenReturn(new CompromisedPasswordDecision(true));

        assertThat(policy(checker).violation("password123")).contains(PasswordPolicy.COMPROMISED_MESSAGE);
    }

    @Test
    void checkerFailure_failsOpen() {
        when(checker.check(anyString())).thenThrow(new ResourceAccessException("timeout"));

        assertThat(policy(checker).violation("correct-horse-battery")).isEmpty();
    }
}
