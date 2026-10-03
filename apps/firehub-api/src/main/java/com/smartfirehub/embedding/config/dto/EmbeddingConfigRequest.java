package com.smartfirehub.embedding.config.dto;

/**
 * 설정 저장·연결 테스트 요청. {@code apiKey} 가 null/빈 값이면 "기존 키 유지"(단, provider·Base URL 이 저장된 값과 같을 때만 — 다른
 * 호스트로 저장된 키를 보내지 않는다).
 */
public record EmbeddingConfigRequest(
    String provider, String model, String baseUrl, String apiKey) {}
