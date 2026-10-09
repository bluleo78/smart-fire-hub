package com.smartfirehub.securitylevel.event;

/**
 * 임베딩 공급자 호스팅 선언이 바뀌었다(EmbeddingSettingsService.save 가 실제 저장값 기준으로 발행). SELF_HOSTED→EXTERNAL 이면 민감
 * 등급 벡터가 정책 위반이 된다(스펙 §4.3 네 번째 정리 트리거). 리스너는 비동기 스레드에서 돌 수 있어 tenantId 를 함께 싣는다.
 */
public record EmbeddingHostingChangedEvent(long tenantId) {}
