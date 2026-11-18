package com.smartfirehub.pipeline.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 파이프라인 수정 요청 — 스텝은 전체 교체다.
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 상세의 {@code updatedAt}(WD-353 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(AI 도구 등 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdatePipelineRequest(
    @NotBlank String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.PIPELINE_TOO_LONG)
        String description,
    Boolean isActive,
    // 전체 교체. @Valid 는 요소 형식에 둔다 — 컨테이너에 붙이면 Hibernate Validator 9 가 HV000271 경고를 낸다(WD-792).
    List<@Valid PipelineStepRequest> steps,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdatePipelineRequest(
      String name, String description, Boolean isActive, List<PipelineStepRequest> steps) {
    this(name, description, isActive, steps, null);
  }
}
