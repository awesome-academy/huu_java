package com.huuhv.foodsndrinks.validation;

import com.huuhv.foodsndrinks.service.security.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

/** Spring tạo validator này (SpringConstraintValidatorFactory) nên inject được {@link PasswordPolicy}. */
@RequiredArgsConstructor
public class NotCompromisedPasswordValidator implements ConstraintValidator<NotCompromisedPassword, String> {

    private final PasswordPolicy passwordPolicy;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return passwordPolicy.compromisedViolation(value).isEmpty();
    }
}
