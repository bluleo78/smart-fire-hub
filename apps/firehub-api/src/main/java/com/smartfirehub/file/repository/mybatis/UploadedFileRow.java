package com.smartfirehub.file.repository.mybatis;

import java.time.Instant;

/**
 * uploaded_files 한 행(조회용, WD-712). 생성 시각을 {@link Instant} 로 읽는다 — 인터페이스 값 객체의 {@code
 * OffsetDateTime} 으로 바로 받으면 Tibero(UTC 벽시계 TIMESTAMP)에서 JVM 시간대로 어긋나므로, 전역 핸들러가 읽는 Instant 를 리포지토리가
 * UTC 로 바꾼다.
 */
public record UploadedFileRow(
    Long id,
    String originalName,
    String mimeType,
    Long fileSize,
    String fileCategory,
    String storagePath,
    Instant createdAt) {}
