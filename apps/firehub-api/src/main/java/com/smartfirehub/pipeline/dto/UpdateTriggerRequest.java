package com.smartfirehub.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 트리거 수정 요청.
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 트리거의 {@code updatedAt}(WD-576 낙관적 잠금, 파이프라인 WD-353 과 같은
 *     방식). 지금 저장된 값과 다르면 409 EDIT_CONFLICT 로 거부한다 — 옛 화면에서 이름만 바꿔 저장해도 그 화면이 들고 있던 옛 cron 이 함께 저장돼
 *     다른 사람의 변경을 경고 없이 되돌리던 결함을 막는다. null 이면 비교하지 않는다(AI 도구 등 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdateTriggerRequest(
    @NotBlank String name,
    Boolean isEnabled,
    String description,
    Map<String, Object> config,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다(WD-576). */
  public UpdateTriggerRequest(
      String name, Boolean isEnabled, String description, Map<String, Object> config) {
    this(name, isEnabled, description, config, null);
  }

  /**
   * config 만 바꾼 사본 — 서비스가 저장 전 config 를 정규화·병합할 때 쓴다. 나머지 필드(기준 버전 포함)를 그대로 넘겨, 필드가 늘어도 정규화 분기마다
   * 손으로 옮기다 빠뜨리지 않게 한다(WD-576).
   */
  public UpdateTriggerRequest withConfig(Map<String, Object> newConfig) {
    return new UpdateTriggerRequest(name, isEnabled, description, newConfig, expectedUpdatedAt);
  }
}
