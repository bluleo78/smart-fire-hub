package com.smartfirehub.dataset.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * API 임포트 요청 — 데이터셋에 API 수집 파이프라인을 만든다.
 *
 * <p>{@code pipelineDescription} 은 새 파이프라인의 설명으로 그대로 저장되므로 파이프라인 생성과 같은 설명 상한을 둔다(WD-520). 예약
 * 설정({@link ScheduleConfig})의 설명은 이번 범위가 아니다.
 */
public record ApiImportRequest(
    String pipelineName,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.PIPELINE_TOO_LONG)
        String pipelineDescription,
    Map<String, Object> apiConfig,
    Long apiConnectionId,
    String loadStrategy,
    boolean executeImmediately,
    ScheduleConfig schedule) {
  public record ScheduleConfig(String cronExpression, String name, String description) {}
}
