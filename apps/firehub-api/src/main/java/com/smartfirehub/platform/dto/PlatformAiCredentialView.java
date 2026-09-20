package com.smartfirehub.platform.dto;

import com.smartfirehub.settings.service.AiCredentialService.AiCredentialView;
import java.util.List;
import java.util.Map;

/**
 * 플랫폼 평면의 AI 자격증명 응답.
 *
 * <p>테넌트 응답({@link AiCredentialView})과 달리 {@code tenantOwned} <b>필드 자체를 두지
 * 않는다.</b> {@code null} 로 채워 내보내면 직렬화된 JSON 에 {@code "tenantOwned":null} 이 남아
 * "값이 있는데 비었다"와 "이 개념이 이 평면에는 없다"가 응답만 봐서 구분되지 않는다(실제로
 * {@code jsonPath(...).doesNotExist()} 는 null 값도 통과시키므로, 그 단언만으로는 이 실수를 잡지
 * 못한다 — 그래서 이 record 는 필드를 아예 갖지 않는다). 상위 평면이 없어 "오버라이드됐는가"라는
 * 개념 자체가 성립하지 않는데, 그렇다고 "항상 false"를 내보내는 것도 거짓이다(플랫폼이 그 값을
 * 소유하는 것이지 아무것도 오버라이드하지 않은 게 아니다) — 그래서 필드를 없애는 것이 유일하게
 * 정직한 선택이다.
 */
public record PlatformAiCredentialView(
    String agentType, Map<String, Object> payload, List<String> secretFieldNames) {

  public static PlatformAiCredentialView from(AiCredentialView view) {
    return new PlatformAiCredentialView(view.agentType(), view.payload(), view.secretFieldNames());
  }
}
