package com.smartfirehub.document.repository.mybatis;

import java.time.Instant;

/**
 * document_file 한 행(조회용, WD-712). 시각을 {@link Instant} 로 읽는다 — Tibero(UTC 벽시계 TIMESTAMP)에서도 JVM 시간대와
 * 무관하게 같은 순간이 되게 하려는 것이고, 응답 DTO 의 {@code LocalDateTime} 변환은 리포지토리가 한다.
 */
public record DocumentFileRow(
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
    Instant createdAt,
    Instant completedAt) {}
