package com.smartfirehub.proactive.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 리포트 양식 섹션 목록의 크기 상한 검증(WD-425).
 *
 * <p>섹션 목록에 상한이 없어 40,000개(11.5MB) 섹션도 저장됐고, 양식 상세 화면이 그 전부를 그린다. 하위 섹션({@code children}, group
 * 타입)까지 합친 섹션 수가 {@link #MAX_SECTIONS} 를 넘거나, 섹션의 글자 항목(지침·내용·설명·이름 등) 하나가 {@link #MAX_TEXT_LENGTH}
 * 자를 넘으면 거부한다(400, {@code sections} 필드 오류). 하위 섹션까지 세는 이유는 group 하나 아래에 섹션을 몰아 넣어 최상위 개수 검사를 우회할 수
 * 있기 때문이다. {@code null} 은 통과시킨다 — 필수 여부는 {@code @NotNull} 이 맡는다.
 */
@Documented
@Constraint(validatedBy = ReportSectionLimitsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReportSectionLimits {

  /** 하위 섹션을 포함한 전체 섹션 수 상한 — 기본 양식은 4~5개, 화면 자동 생성도 4개라 넉넉하다. */
  int MAX_SECTIONS = 100;

  /** 섹션의 글자 항목 하나의 최대 길이(자). */
  int MAX_TEXT_LENGTH = 10_000;

  /** 섹션 수 초과 안내. */
  String TOO_MANY_SECTIONS_MESSAGE = "리포트 섹션은 하위 섹션을 포함해 최대 100개까지 만들 수 있습니다.";

  /** 글자 항목 길이 초과 안내. */
  String TEXT_TOO_LONG_MESSAGE = "리포트 섹션의 지침·내용 등 글자 항목은 항목마다 최대 10,000자까지 입력할 수 있습니다.";

  String message() default TOO_MANY_SECTIONS_MESSAGE;

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
