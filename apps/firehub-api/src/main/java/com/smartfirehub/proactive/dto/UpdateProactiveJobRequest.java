package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Proactive Job 수정 요청 DTO.
 *
 * <p>{@code triggerType}은 nullable이며 null이면 기존 값을 유지한다. 이 필드가 없어 트리거 유형 변경이 DB에 반영되지 않던 회귀를 수정했다
 * (#655).
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 작업의 {@code updatedAt}(WD-364 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(AI 도구·목록의 켜기 토글 등 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdateProactiveJobRequest(
    String name,
    String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    Boolean enabled,
    @Pattern(
            regexp = "SCHEDULE|ANOMALY|BOTH",
            message = "triggerType은 SCHEDULE, ANOMALY, BOTH 중 하나여야 합니다")
        String triggerType,
    Map<String, Object> config,
    // templateId 가 null 인 이유가 "미제공(변경 없음)"인지 "기본 형식으로 되돌림"인지 Long 하나로는
    // 구분할 수 없어 별도 플래그로 분리한다(WD-136, 대시보드 clearAutoRefresh #568 과 같은 방식).
    // true 면 templateId 가 null 이어도 지정된 템플릿을 실제로 해제한다. AI 도구처럼 바꿀 필드만 보내는
    // 호출은 이 값을 보내지 않으므로 템플릿이 그대로 유지된다.
    Boolean clearTemplate,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 형태(템플릿 해제 플래그 포함) — 내부 호출·테스트 호환용. 비교하지 않는다. */
  public UpdateProactiveJobRequest(
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config,
      Boolean clearTemplate) {
    this(
        name,
        prompt,
        templateId,
        cronExpression,
        timezone,
        enabled,
        triggerType,
        config,
        clearTemplate,
        null);
  }

  /** 템플릿 해제 플래그 없이 만드는 기존 형태 — 내부 호출·테스트 호환용. */
  public UpdateProactiveJobRequest(
      String name,
      String prompt,
      Long templateId,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config) {
    this(
        name,
        prompt,
        templateId,
        cronExpression,
        timezone,
        enabled,
        triggerType,
        config,
        null,
        null);
  }
}
