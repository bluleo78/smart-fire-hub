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
    Long sourcePipelineStepId,
    String bucket, // FILE 전용: MinIO 버킷 (null이면 기본 버킷 사용)
    String prefix) { // FILE 전용: 오브젝트 프리픽스 (null이면 빈 문자열)

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

  // 기존 8-인자 호출부(TABLE/DOCUMENT 등 bucket/prefix 불필요)와의 하위 호환용 보조 생성자.
  // FILE 데이터셋이 아닌 기존 호출부를 전부 수정하지 않기 위해 bucket/prefix 를 null 로 위임한다.
  public CreateDatasetRequest(
      String name,
      String tableName,
      String description,
      Long categoryId,
      String storageType,
      String originType,
      List<DatasetColumnRequest> columns,
      Long sourcePipelineStepId) {
    this(
        name,
        tableName,
        description,
        categoryId,
        storageType,
        originType,
        columns,
        sourcePipelineStepId,
        null,
        null);
  }
}
