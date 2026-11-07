package com.smartfirehub.dataset.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 데이터셋 이름·설명·카테고리 수정 요청.
 *
 * <p>수정은 세 칸을 통째로 바꾸는 전체 교체라, 두 사람이 같은 데이터셋을 열어 두고 각자 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다(WD-575).
 * 그래서 차트·쿼리·대시보드·파이프라인처럼 편집 기준 버전을 함께 받는다.
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 데이터셋의 {@code updatedAt}(WD-575 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(기준을 보내지 않는 기존 호출부·AI 도구, "내 변경으로 덮어쓰기").
 */
public record UpdateDatasetRequest(
    @NotBlank String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.DATASET_TOO_LONG)
        String description,
    Long categoryId,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdateDatasetRequest(String name, String description, Long categoryId) {
    this(name, description, categoryId, null);
  }
}
