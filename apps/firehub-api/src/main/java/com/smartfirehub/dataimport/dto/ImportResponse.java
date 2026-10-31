package com.smartfirehub.dataimport.dto;

import java.time.LocalDateTime;

public record ImportResponse(
    Long id,
    Long datasetId,
    String fileName,
    Long fileSize,
    String fileType,
    String status,
    Integer totalRows,
    Integer successRows,
    Integer errorRows,
    // 업서트·교체(PK)에서 파일 안 같은 키 때문에 마지막 행만 반영하고 버린 앞 행 수(WD-387). PK 경로가 아니면 null.
    Integer duplicateKeyRows,
    Object errorDetails,
    String errorMessage,
    String importedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt) {}
