package com.smartfirehub.embedding.config.dto;

/**
 * 설정 저장·연결 테스트 요청. {@code apiKey} 가 null/빈 값이면 "기존 키 유지"(단, provider·Base URL 이 저장된 값과 같을 때만 — 다른
 * 호스트로 저장된 키를 보내지 않는다). {@code hosting} 은 공급자 호스팅 위치 선언("EXTERNAL"|"SELF_HOSTED") — null 이면
 * provider·Base URL 이 그대로일 때만 기존 선언 유지(바뀌면 외부), 그 외 값은 400.
 */
public record EmbeddingConfigRequest(
    String provider, String model, String baseUrl, String apiKey, String hosting) {}
