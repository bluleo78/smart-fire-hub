package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateDatasetRequest(
    @NotBlank String name,
    @NotBlank String tableName,
    String description,
    Long categoryId,
    String storageType,
    String originType,
    List<DatasetColumnRequest> columns,
    Long sourcePipelineStepId) {

  public CreateDatasetRequest {
    // 저장 방식 기본값: 행·열 테이블
    if (storageType == null) {
      storageType = "TABLE";
    }
    // 출처 기본값: 직접 수집 원본
    if (originType == null) {
      originType = "SOURCE";
    }
  }
}
