package com.huuhv.foodsndrinks.validation;

import com.huuhv.foodsndrinks.service.security.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

/** Spring tạo validator này (SpringConstraintValidatorFactory) nên inject được {@link PasswordPolicy}. */
@RequiredArgsConstructor
public class PasswordPolicyValidator implements ConstraintValidator<ValidPassword, String> {

    private final PasswordPolicy passwordPolicy;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return passwordPolicy.violation(value)
                .map(message -> {
                    context.disableDefaultConstraintViolation();
                    // message là hằng số của PasswordPolicy (không chứa dữ liệu người dùng) nên dùng làm template an toàn
                    context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
                    return false;
                })
                .orElse(true);
    }
}
