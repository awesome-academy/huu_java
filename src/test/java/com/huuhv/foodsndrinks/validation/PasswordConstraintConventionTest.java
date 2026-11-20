package com.huuhv.foodsndrinks.validation;

import com.huuhv.foodsndrinks.dto.request.RegisterReqDto;
import com.huuhv.foodsndrinks.service.security.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.GroupSequence;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mọi field mật khẩu mới ({@code @ValidPassword}) phải có cả {@code @NotCompromisedPassword} trong group
 * {@link PasswordBreachCheck}, và DTO phải xếp group đó sau group mặc định bằng {@code @GroupSequence} — nếu không, lần
 * gọi Pwned Passwords chạy cả khi field khác của form sai (hoặc không chạy).
 */
class PasswordConstraintConventionTest {

    private static List<Class<?>> requestDtos() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.huuhv.foodsndrinks.dto.request")) {
            classes.add(Class.forName(candidate.getBeanClassName()));
        }
        return classes;
    }

    @Test
    void everyNewPasswordField_hasBreachCheckOrderedLast() throws Exception {
        List<Class<?>> withPassword = requestDtos().stream()
                .filter(type -> Arrays.stream(type.getDeclaredFields()).anyMatch(f -> f.isAnnotationPresent(ValidPassword.class)))
                .toList();
        assertThat(withPassword).as("DTO có @ValidPassword").isNotEmpty();

        for (Class<?> type : withPassword) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(ValidPassword.class)) {
                    continue;
                }
                NotCompromisedPassword breach = field.getAnnotation(NotCompromisedPassword.class);
                assertThat(breach).as("%s.%s thiếu @NotCompromisedPassword", type.getSimpleName(), field.getName())
                        .isNotNull();
                assertThat(breach.groups()).as("%s.%s", type.getSimpleName(), field.getName())
                        .containsExactly(PasswordBreachCheck.class);
            }
            GroupSequence sequence = type.getAnnotation(GroupSequence.class);
            assertThat(sequence).as("%s thiếu @GroupSequence", type.getSimpleName()).isNotNull();
            assertThat(sequence.value()).as(type.getSimpleName()).containsExactly(type, PasswordBreachCheck.class);
        }
    }

    // -------------------------------------------------------
    // Hành vi: Pwned Passwords chỉ được gọi khi mọi field khác đã hợp lệ
    // -------------------------------------------------------

    private final CompromisedPasswordChecker checker = mock(CompromisedPasswordChecker.class);

    private Validator validator() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CompromisedPasswordChecker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(checker);
        PasswordPolicy policy = new PasswordPolicy(provider);
        ConstraintValidatorFactory factory = new ConstraintValidatorFactory() {
            @Override
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                if (key == PasswordPolicyValidator.class) {
                    return key.cast(new PasswordPolicyValidator(policy));
                }
                if (key == NotCompromisedPasswordValidator.class) {
                    return key.cast(new NotCompromisedPasswordValidator(policy));
                }
                try {
                    return key.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {
            }
        };
        return Validation.byDefaultProvider().configure().constraintValidatorFactory(factory)
                .buildValidatorFactory().getValidator();
    }

    private static RegisterReqDto register(String email) {
        return new RegisterReqDto("Policy Tester", "policytester", email, "0912345671",
                "password123", "password123");
    }

    @Test
    void breachCheck_isSkipped_whenAnotherFieldIsInvalid() {
        Set<ConstraintViolation<RegisterReqDto>> violations = validator().validate(register("not-an-email"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("email");
        verifyNoInteractions(checker);
    }

    @Test
    void breachCheck_runs_whenEverythingElseIsValid() {
        when(checker.check("password123")).thenReturn(new CompromisedPasswordDecision(true));

        Set<ConstraintViolation<RegisterReqDto>> violations = validator().validate(register("tester@example.com"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("password");
    }
}
