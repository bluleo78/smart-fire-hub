package com.smartfirehub.pipeline.repository.mybatis;

import java.time.LocalDateTime;

/** TriggerEventMapper 의 평평한 행 모음(WD-708). */
public final class TriggerEventRows {

  private TriggerEventRows() {}

  /** 이벤트 한 줄 — detail 은 JSON 문자열 그대로(구현이 맵으로 푼다). triggerName 은 트리거가 없으면 null. */
  public record EventRow(
      Long id,
      Long triggerId,
      String triggerName,
      String eventType,
      Long executionId,
      String detailJson,
      LocalDateTime createdAt) {}
}
