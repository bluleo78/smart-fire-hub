package com.smartfirehub.proactive.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;
import java.util.Map;

/**
 * {@link ReportSectionLimits} 검증기(WD-425) — 섹션 트리를 내려가며 섹션 수와 글자 항목 길이를 확인한다.
 *
 * <p>섹션 수가 상한을 넘는 즉시 멈추므로 거대한 입력이라도 상한 + 1 개까지만 살펴본다. 안내 문구는 상수만 쓰고 사용자 입력(섹션 키·값)은 넣지 않는다 — 문구
 * 템플릿의 {@code {}} / {@code ${}} 해석에 사용자 값이 섞이지 않게 하기 위해서다.
 */
public class ReportSectionLimitsValidator
    implements ConstraintValidator<ReportSectionLimits, List<?>> {

  @Override
  public boolean isValid(List<?> sections, ConstraintValidatorContext context) {
    if (sections == null) {
      return true;
    }
    int[] count = {0};
    String violation = check(sections, count);
    if (violation == null) {
      return true;
    }
    // 위반 종류에 맞는 안내로 바꾼다 — 필드 경로는 그대로 두어 오류 키가 sections 로 남게 한다.
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate(violation).addConstraintViolation();
    return false;
  }

  /**
   * 섹션 목록(하위 포함)을 확인해 첫 위반의 안내 문구를 돌려준다. 위반이 없으면 {@code null}.
   *
   * @param count 지금까지 센 섹션 수(하위 호출과 공유)
   */
  private String check(List<?> sections, int[] count) {
    for (Object item : sections) {
      if (++count[0] > ReportSectionLimits.MAX_SECTIONS) {
        return ReportSectionLimits.TOO_MANY_SECTIONS_MESSAGE;
      }
      if (!(item instanceof Map<?, ?> section)) {
        continue;
      }
      for (Map.Entry<?, ?> entry : section.entrySet()) {
        Object value = entry.getValue();
        if (value instanceof String text && text.length() > ReportSectionLimits.MAX_TEXT_LENGTH) {
          return ReportSectionLimits.TEXT_TOO_LONG_MESSAGE;
        }
      }
      // group 타입의 하위 섹션도 같은 규칙으로 센다.
      if (section.get("children") instanceof List<?> children) {
        String nested = check(children, count);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }
}
