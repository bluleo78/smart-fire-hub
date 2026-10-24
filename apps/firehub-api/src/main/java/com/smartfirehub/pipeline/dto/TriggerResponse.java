package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code nextFireTime}: SCHEDULE 트리거의 다음 실행 예정 시각 (UTC ISO-8601, 예: "2026-09-13T00:00:00Z"). {@code
 * pipeline_trigger.trigger_state}(jsonb)에 저장된 값을 리포지토리가 꺼내 이 필드로 매핑한다 — 프론트엔드가 {@code
 * trigger.nextFireTime}으로 바로 접근하기 위함(#676). SCHEDULE이 아니거나 아직 등록 전이거나 비활성이면 null(WD-114).
 *
 * <p>{@code upstreamMissing}: PIPELINE_CHAIN 트리거의 선행 파이프라인이 (지금 테넌트에) 없으면 true(WD-218). 저장값이 아니라 조회
 * 시 계산한다 — 선행이 삭제되면 서버가 트리거를 끄지만 이유를 남길 자리가 없어 화면에 이유 없는 「비활성」만 보였고, 결함 이전에 없는 선행으로 켜진 채 남은 트리거도 함께
 * 드러내기 위함이다. 다른 유형은 늘 false.
 */
public record TriggerResponse(
    Long id,
    Long pipelineId,
    String triggerType,
    String name,
    String description,
    boolean isEnabled,
    Map<String, Object> config,
    Map<String, Object> triggerState,
    String nextFireTime,
    Long createdBy,
    LocalDateTime createdAt,
    boolean upstreamMissing) {

  /** 선행 없음 계산 전의 응답(저장소 매핑 등) — upstreamMissing 은 false 로 두고 서비스가 조회 시 채운다. */
  public TriggerResponse(
      Long id,
      Long pipelineId,
      String triggerType,
      String name,
      String description,
      boolean isEnabled,
      Map<String, Object> config,
      Map<String, Object> triggerState,
      String nextFireTime,
      Long createdBy,
      LocalDateTime createdAt) {
    this(
        id,
        pipelineId,
        triggerType,
        name,
        description,
        isEnabled,
        config,
        triggerState,
        nextFireTime,
        createdBy,
        createdAt,
        false);
  }

  /** 선행 없음 표시를 붙인 사본. */
  public TriggerResponse withUpstreamMissing(boolean missing) {
    return new TriggerResponse(
        id,
        pipelineId,
        triggerType,
        name,
        description,
        isEnabled,
        config,
        triggerState,
        nextFireTime,
        createdBy,
        createdAt,
        missing);
  }
}
