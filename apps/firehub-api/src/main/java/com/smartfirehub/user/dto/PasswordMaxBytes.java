package com.smartfirehub.user.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 설정하는 비밀번호의 UTF-8 바이트 상한({@link PasswordRules#MAX_BYTES}) 검증(WD-605).
 *
 * <p>{@code @Size} 는 글자 수만 잰다 — 한글 1자는 UTF-8 3바이트라 글자 수로는 BCrypt 의 72바이트 상한을 표현할 수 없어 따로 둔다. 로그인·현재
 * 비밀번호 칸에는 달지 않는다(예전에 길게 만든 비밀번호의 기존 사용자를 막지 않기 위해). {@code null} 은 통과 — 필수 여부는 {@code @NotBlank} 가
 * 맡는다.
 */
@Documented
@Constraint(validatedBy = PasswordMaxBytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordMaxBytes {

  String message() default PasswordRules.MAX_BYTES_MESSAGE;

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
