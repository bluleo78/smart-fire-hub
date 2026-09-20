package com.smartfirehub.settings.dto;

import java.util.Map;

/**
 * {@code PUT /settings/ai-credential} (테넌트/플랫폼 공용) 요청 바디.
 *
 * <p>{@link com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert} 와 필드가
 * 같지만 별도 record 를 둔다 — 그 record 는 {@code payload}/{@code secret} 이 {@code null} 이면
 * {@code forEach} 에서 그대로 NPE 가 나(계약은 400) 서비스 내부 전용으로 남겨두고, 이 클래스가
 * 정규화를 맡는다. 화면이 "유형만 바꾸는" 요청처럼 {@code payload}/{@code secret} 을 통째로
 * 생략해 보낼 수 있으므로, 정식 캐노니컬 접근자를 오버라이드해 {@code null} 을 빈 맵으로
 * 바꾼다(레코드 컴포넌트 접근자는 명시적으로 재정의할 수 있다).
 */
public record AiCredentialUpsertRequest(
    String agentType, Map<String, Object> payload, Map<String, String> secret) {

  @Override
  public Map<String, Object> payload() {
    return payload == null ? Map.of() : payload;
  }

  @Override
  public Map<String, String> secret() {
    return secret == null ? Map.of() : secret;
  }
}
