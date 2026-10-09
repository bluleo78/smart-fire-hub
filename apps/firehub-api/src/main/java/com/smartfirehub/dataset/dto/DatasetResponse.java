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
    LocalDateTime securityLevelAutoRaisedAt,
    // 조회자 기준 내보내기 가능 여부(S4, 스펙 §4.4). 리포지토리는 조회자를 모르므로 서비스가 withExportAllowed 로 채운다.
    boolean exportAllowed) {

  /** exportAllowed 를 모르는 생성 경로(리포지토리 매핑)용 호환 생성자 — 내보내기 불가(false, fail-closed). */
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
      Long sourcePipelineStepId,
      SecurityLevelSummary securityLevel,
      LocalDateTime securityLevelAutoRaisedAt) {
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
        securityLevel,
        securityLevelAutoRaisedAt,
        false);
  }

  /** 조회자별 내보내기 가능 여부를 채운 사본 — 리포지토리는 조회자를 모른다. */
  public DatasetResponse withExportAllowed(boolean allowed) {
    return new DatasetResponse(
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
        securityLevel,
        securityLevelAutoRaisedAt,
        allowed);
  }

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
        null,
        false);
  }
}
