package com.smartfirehub.settings.service;

import java.util.Set;

/**
 * 테넌트가 자기 값으로 덮어쓸 수 있는 설정 키의 <b>단일 출처</b>(설계서 §4.5).
 *
 * <p><b>왜 deny-list 가 아니라 allow-list 인가</b>: 목록에 없는 키는 자동으로 플랫폼 잠금이
 * 되므로, 장래에 누가 설정 키를 하나 추가하고 이 파일을 잊어도 그 키가 테넌트에게 열리지
 * 않는다(fail-closed). deny-list 라면 정반대로 새 키가 기본 개방된다.
 *
 * <p><b>왜 DB 가 아니라 코드 상수인가</b>: 재분류가 마이그레이션이 아니라 한 줄 편집이 되고,
 * 분류 근거를 주석으로 코드에 붙여 둘 수 있다.
 *
 * <p>여기 없는 12키가 플랫폼 잠금인 이유: SMTP 6키는 발신 도메인 신뢰도를 전 테넌트가 공유하고,
 * {@code embedding.*} 4키는 모델 변경이 벡터 차원을 바꿔 <b>기존 임베딩 전량을 무효화</b>하며,
 * {@code ai.api_key}/{@code ai.cli_oauth_token}/{@code ai.agent_type} 은 과금 주체와 실행 형태라
 * BYO 키 정책이 정해질 때까지 플랫폼이 갖는다.
 */
public final class SettingsOverridePolicy {

  /**
   * 오버라이드 허용 키. 전부 "테넌트의 업무 성격에 종속되고, 파괴적이지 않고, 과금 주체를
   * 바꾸지 않는다"는 기준을 만족한다({@code api_key} 가 플랫폼 소유이므로 비용은 플랫폼이 진다).
   */
  private static final Set<String> TENANT_OVERRIDABLE =
      Set.of(
          "ai.system_prompt",
          "ai.model",
          "ai.temperature",
          "ai.max_turns",
          "ai.max_tokens",
          // 스펙 §4.5 표에는 없지만 ALLOWED_AI_KEYS 에 실재하는 키다. 세션 토큰 상한은 테넌트
          // 업무 성격에 종속되고 파괴적이지 않아 같은 기준으로 허용한다(계획서 Ruling 참조).
          "ai.session_max_tokens");

  private SettingsOverridePolicy() {}

  /** 이 키를 테넌트가 자기 값으로 덮어쓸 수 있는가. null·미등록 키는 모두 false(플랫폼 잠금). */
  public static boolean isTenantOverridable(String key) {
    return key != null && TENANT_OVERRIDABLE.contains(key);
  }

  /** 오버라이드 허용 키 전체. web 이 노출 대상을 정하는 데도 쓰인다(§6). */
  public static Set<String> tenantOverridableKeys() {
    return TENANT_OVERRIDABLE;
  }
}
