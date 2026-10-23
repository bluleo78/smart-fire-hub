package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 스마트 작업 응답.
 *
 * <p>{@code ownerEligible}·{@code ownerIneligibleReason}(WD-192): 작업 주인이 지금 실행 자격이 있는지. 주인이
 * 정지·제거되거나 스마트 작업 쓰기 권한을 잃으면 작업은 「활성」인 채 실행 때마다 건너뛴다(WD-166 — 자동으로 끄지 않는다). 예전엔 목록·상세가 「활성 · 다음 실행
 * …」으로 정상처럼 보였으므로 같은 판정({@code ProactiveOwnerEligibility})을 응답에 싣는다. 사유 코드는 {@code OWNER_INACTIVE}
 * / {@code OWNER_NO_PERMISSION}, 자격이 있으면 null 이다. 판정을 하지 않은 내부 경로(스케줄러·실행기)의 응답은 둘 다 null 이다.
 */
public record ProactiveJobResponse(
    Long id,
    Long userId,
    Long templateId,
    String templateName,
    String name,
    String prompt,
    String cronExpression,
    String timezone,
    Boolean enabled,
    String triggerType,
    Map<String, Object> config,
    LocalDateTime lastExecutedAt,
    LocalDateTime nextExecuteAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    ProactiveJobExecutionResponse lastExecution,
    Boolean ownerEligible,
    String ownerIneligibleReason) {

  /** 자격 판정 전 응답 — 저장소 조회·내부 경로가 쓴다. 자격 필드는 서비스가 {@link #withOwnerEligibility} 로 채운다. */
  public ProactiveJobResponse(
      Long id,
      Long userId,
      Long templateId,
      String templateName,
      String name,
      String prompt,
      String cronExpression,
      String timezone,
      Boolean enabled,
      String triggerType,
      Map<String, Object> config,
      LocalDateTime lastExecutedAt,
      LocalDateTime nextExecuteAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      ProactiveJobExecutionResponse lastExecution) {
    this(
        id,
        userId,
        templateId,
        templateName,
        name,
        prompt,
        cronExpression,
        timezone,
        enabled,
        triggerType,
        config,
        lastExecutedAt,
        nextExecuteAt,
        createdAt,
        updatedAt,
        lastExecution,
        null,
        null);
  }

  /**
   * 마지막 실행 결과만 바꾼 사본을 돌려준다.
   *
   * <p>잡 행 조회와 최근 실행 조회는 서로 다른 테이블이라 서비스에서 따로 읽어 합친다 (WD-134). 레코드 필드가 많아 호출처마다 생성자를 다시 쓰지 않도록 여기 한
   * 곳에 둔다.
   */
  public ProactiveJobResponse withLastExecution(ProactiveJobExecutionResponse execution) {
    return new ProactiveJobResponse(
        id,
        userId,
        templateId,
        templateName,
        name,
        prompt,
        cronExpression,
        timezone,
        enabled,
        triggerType,
        config,
        lastExecutedAt,
        nextExecuteAt,
        createdAt,
        updatedAt,
        execution,
        ownerEligible,
        ownerIneligibleReason);
  }

  /**
   * 작업 주인 실행 자격 판정을 실은 사본(WD-192).
   *
   * @param ineligibleCode 자격이 없으면 사유 코드, 있으면 빈 값 ({@code
   *     ProactiveOwnerEligibility.findIneligibilityCode})
   */
  public ProactiveJobResponse withOwnerEligibility(Optional<String> ineligibleCode) {
    return new ProactiveJobResponse(
        id,
        userId,
        templateId,
        templateName,
        name,
        prompt,
        cronExpression,
        timezone,
        enabled,
        triggerType,
        config,
        lastExecutedAt,
        nextExecuteAt,
        createdAt,
        updatedAt,
        lastExecution,
        ineligibleCode.isEmpty(),
        ineligibleCode.orElse(null));
  }
}
