package com.huuhv.foodsndrinks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mật khẩu mới không được nằm trong danh sách bị lộ ({@code PasswordPolicy#compromisedViolation}). Luôn khai báo kèm
 * {@link ValidPassword} với {@code groups = PasswordBreachCheck.class}, và DTO phải có
 * {@code @GroupSequence({TheDto.class, PasswordBreachCheck.class})} (test {@code PasswordConstraintConventionTest}).
 * Null/chuỗi rỗng được coi là hợp lệ.
 */
@Documented
@Constraint(validatedBy = NotCompromisedPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotCompromisedPassword {

    String message() default "Mật khẩu này đã xuất hiện trong các vụ lộ dữ liệu, vui lòng chọn mật khẩu khác.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
