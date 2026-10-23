package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;

/**
 * 저장 쿼리 목록 항목.
 *
 * @param createdBy 작성자 id(WD-196) — 웹이 현재 사용자와 비교해 남의 공유 쿼리 줄에서 삭제·편집 버튼을 숨긴다. 대시보드·차트 목록과 쿼리
 *     상세 응답은 이미 싣고 있었다.
 */
public record SavedQueryListResponse(
    Long id,
    String name,
    String description,
    String folder,
    Long datasetId,
    String datasetName,
    boolean isShared,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long chartCount) {}
