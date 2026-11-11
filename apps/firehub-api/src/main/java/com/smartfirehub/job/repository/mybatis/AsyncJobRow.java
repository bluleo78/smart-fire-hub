package com.smartfirehub.job.repository.mybatis;

import java.time.LocalDateTime;

/**
 * async_job 한 행의 원시 값(WD-706) — 매퍼가 생성자로 채우고, 구현이 metadata JSON 문자열을 맵으로 풀어 {@code
 * AsyncJobStatusResponse} 로 바꾼다. 매퍼 결과를 응답 DTO 로 바로 받지 않는 이유: 응답의 metadata 는 {@code Map} 이라 JSON
 * 파싱(실패 시 빈 맵)을 jOOQ 구현과 같은 규칙으로 자바에서 해야 한다.
 */
public record AsyncJobRow(
    String id,
    String jobType,
    String stage,
    Integer progress,
    String message,
    String metadata,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Long userId) {}
