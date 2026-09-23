package com.smartfirehub.settings.dto;

import java.util.Map;

/**
 * {@code PUT /settings/ai-classify-credential} 요청 바디(#707). 자격증명 필드는
 * {@link AiCredentialUpsertRequest} 와 같은 계약(생략 secret=유지, ""=삭제)이고 {@code model} 이 더해진다.
 * {@code payload}/{@code secret} 생략은 빈 맵으로 정규화한다(같은 이유 — 그 클래스 javadoc 참고).
 */
public record AiClassifyCredentialUpsertRequest(
    String agentType, Map<String, Object> payload, Map<String, String> secret, String model) {

  @Override
  public Map<String, Object> payload() {
    return payload == null ? Map.of() : payload;
  }

  @Override
  public Map<String, String> secret() {
    return secret == null ? Map.of() : secret;
  }
}
