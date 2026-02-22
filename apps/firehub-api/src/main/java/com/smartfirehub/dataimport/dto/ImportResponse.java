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
    Object errorDetails,
    String importedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt) {}
