package com.smartfirehub.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 자유 형식 JSON 값(Map·List 등)의 직렬화 크기 상한 검증(WD-425).
 *
 * <p>차트 {@code config}·스마트 작업 {@code config} 처럼 구조를 정하지 않은 JSON 은 {@code @Size} 로 길이를 잴 수 없어 20MB 도
 * 그대로 저장됐다. 저장된 값은 목록 응답에 통째로 실려, 공유 항목 하나가 모든 사용자의 목록을 수 MB 로 부풀린다. 앱의 {@code ObjectMapper} 로 다시
 * 직렬화한 UTF-8 바이트 수가 {@link #maxBytes()} 를 넘으면 거부한다(400, 필드 오류). {@code null} 은 통과시킨다 — 필수 여부는
 * {@code @NotNull} 이 맡는다.
 */
@Documented
@Constraint(validatedBy = MaxJsonSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxJsonSize {

  /** 허용하는 최대 직렬화 크기(UTF-8 바이트). */
  long maxBytes();

  String message() default "JSON 값이 너무 큽니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
