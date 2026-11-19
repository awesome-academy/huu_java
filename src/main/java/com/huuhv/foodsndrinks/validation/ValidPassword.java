package com.huuhv.foodsndrinks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mật khẩu mới phải đạt {@code PasswordPolicy} (8–64 ký tự, không nằm trong danh sách bị lộ). Null/chuỗi rỗng được
 * coi là hợp lệ ("không đổi mật khẩu"); trường bắt buộc thì thêm {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "Mật khẩu không hợp lệ.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
