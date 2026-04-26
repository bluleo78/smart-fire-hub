package com.smartfirehub.apiconnection.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * API 연결 응답 DTO. Phase 9: baseUrl, healthCheckPath, 헬스체크 상태 필드 추가. maskedAuthConfig — 민감 키(apiKey,
 * token 등)는 마스킹 처리된 값.
 */
public record ApiConnectionResponse(
    Long id,
    String name,
    String description,
    String authType,
    Map<String, String> maskedAuthConfig,
    String baseUrl,
    String healthCheckPath,
    String lastStatus,
    LocalDateTime lastCheckedAt,
    Long lastLatencyMs,
    String lastErrorMessage,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
