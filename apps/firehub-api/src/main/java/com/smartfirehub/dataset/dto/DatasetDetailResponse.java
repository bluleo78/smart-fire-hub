package com.smartfirehub.dataset.dto;

import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import java.time.LocalDateTime;
import java.util.List;

public record DatasetDetailResponse(
    Long id,
    String name,
    String tableName,
    String description,
    CategoryResponse category,
    String storageType,
    String originType,
    String createdBy,
    List<DatasetColumnResponse> columns,
    long rowCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String updatedBy,
    boolean isFavorite,
    List<String> tags,
    String status,
    String statusNote,
    String statusUpdatedBy,
    LocalDateTime statusUpdatedAt,
    List<LinkedPipelineInfo> linkedPipelines,
    Long sourcePipelineStepId,
    // 보안 등급(S1). 상세 헤더 배지와 「보안」 탭 정책 칩이 쓴다.
    SecurityLevelSummary securityLevel,
    LocalDateTime securityLevelAutoRaisedAt) {

  /** 등급을 모르는 생성 경로(테스트)용 호환 생성자 — 등급 필드는 null. */
  public DatasetDetailResponse(
      Long id,
      String name,
      String tableName,
      String description,
      CategoryResponse category,
      String storageType,
      String originType,
      String createdBy,
      List<DatasetColumnResponse> columns,
      long rowCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      String updatedBy,
      boolean isFavorite,
      List<String> tags,
      String status,
      String statusNote,
      String statusUpdatedBy,
      LocalDateTime statusUpdatedAt,
      List<LinkedPipelineInfo> linkedPipelines,
      Long sourcePipelineStepId) {
    this(
        id,
        name,
        tableName,
        description,
        category,
        storageType,
        originType,
        createdBy,
        columns,
        rowCount,
        createdAt,
        updatedAt,
        updatedBy,
        isFavorite,
        tags,
        status,
        statusNote,
        statusUpdatedBy,
        statusUpdatedAt,
        linkedPipelines,
        sourcePipelineStepId,
        null,
        null);
  }

  public record LinkedPipelineInfo(Long id, String name, boolean isActive) {}
}
