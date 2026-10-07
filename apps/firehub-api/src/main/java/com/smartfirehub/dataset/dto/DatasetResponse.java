package com.smartfirehub.dataset.dto;

import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import java.time.LocalDateTime;
import java.util.List;

public record DatasetResponse(
    Long id,
    String name,
    String tableName,
    String description,
    CategoryResponse category,
    String storageType,
    String originType,
    LocalDateTime createdAt,
    boolean isFavorite,
    List<String> tags,
    String status,
    String statusNote,
    String statusUpdatedBy,
    LocalDateTime statusUpdatedAt,
    Long sourcePipelineStepId,
    // 보안 등급(S1). 목록·상세 배지와 등급 필터 UI 가 쓴다.
    SecurityLevelSummary securityLevel,
    LocalDateTime securityLevelAutoRaisedAt) {

  /** 등급을 모르는 생성 경로(테스트·save 직후 매핑)용 호환 생성자 — 등급 필드는 null. */
  public DatasetResponse(
      Long id,
      String name,
      String tableName,
      String description,
      CategoryResponse category,
      String storageType,
      String originType,
      LocalDateTime createdAt,
      boolean isFavorite,
      List<String> tags,
      String status,
      String statusNote,
      String statusUpdatedBy,
      LocalDateTime statusUpdatedAt,
      Long sourcePipelineStepId) {
    this(
        id,
        name,
        tableName,
        description,
        category,
        storageType,
        originType,
        createdAt,
        isFavorite,
        tags,
        status,
        statusNote,
        statusUpdatedBy,
        statusUpdatedAt,
        sourcePipelineStepId,
        null,
        null);
  }
}
