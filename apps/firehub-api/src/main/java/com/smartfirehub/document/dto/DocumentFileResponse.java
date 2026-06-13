package com.smartfirehub.document.dto;

import java.time.LocalDateTime;

/** 문서 파일 메타 응답. */
public record DocumentFileResponse(
    Long id,
    Long datasetId,
    String originalName,
    String mimeType,
    long fileSize,
    String status,
    Integer pageCount,
    Integer chunkCount,
    String errorDetail,
    Long uploadedBy,
    LocalDateTime createdAt,
    LocalDateTime completedAt) {}
