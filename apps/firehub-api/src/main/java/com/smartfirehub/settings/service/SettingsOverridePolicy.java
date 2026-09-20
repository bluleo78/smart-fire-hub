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
 * <p>여기 없는 4키({@code embedding.*})가 플랫폼 잠금인 이유: 모델 변경이 벡터 차원을 바꿔
 * <b>기존 임베딩 전량을 무효화</b>하므로, Phase B 가 차원별 컬럼을 넣을 때까지 플랫폼이 갖는다.
 *
 * <p><b>AI 자격증명은 3개의 평면 키가 아니라 {@link AiCredentialService#KEY} 하나다(타입형 전환,
 * 2026-09).</b> 예전에는 {@code ai.api_key}/{@code ai.cli_oauth_token}/{@code ai.agent_type} 3키가
 * 여기 있었고, 과금 주체가 섞이지 않는 것은 {@code SettingsService} 의 번들 규칙({@code
 * AI_CREDENTIAL_KEYS} — 3키 중 하나라도 테넌트 행이 있으면 셋 다 원자적으로 같은 평면에서
 * 해석)이 보장했다. 지금은 그 3키를 이 화이트리스트에서 빼고 {@link AiCredentialService#KEY}
 * (JSON 블롭) 하나만 넣는다 — 원자성은 더 이상 "3키를 함께 해석하는 규칙"이 필요 없다, 문서가
 * 하나라 재정의가 항상 통째이기 때문이다. 이 키의 <b>범용 쓰기·단일 읽기는 여전히 막혀 있다</b>
 * ({@code SettingsService.rejectBundleKey}) — 비밀이 하위 필드에 있어 검증·암호화를
 * {@link AiCredentialService} 가 전담해야 한다. 옛 3키는 플랫폼 기본값 전용으로 남는다
 * ({@code SettingsService.ALLOWED_AI_KEYS}) — 아무도 읽지 않는 쓰기 전용 레거시 값이다.
 *
 * <p><b>SMTP 6키는 P7-c1(2026-08-22)에 플랫폼 잠금에서 여기로 재분류됐다.</b> v1 은 "발신 도메인
 * 신뢰도를 전 테넌트가 공유한다"를 근거로 플랫폼 잠금이었으나, "자기 조직 명의로 메일을 보낸다"는
 * 테넌트 요구가 앞선다고 판단했다. {@code embedding.model} 과 결정적으로 다른 점: 값을 바꿔도
 * 기존 데이터가 무효화되지 않는다 — 파괴 버튼이 아니라 되돌릴 수 있는 설정이다. 미설정 테넌트는
 * 플랫폼 기본값(운영자가 편집)으로 폴백한다.
 */
public final class SettingsOverridePolicy {

  /**
   * 오버라이드 허용 키. 전부 "테넌트의 업무 성격에 종속되고, 파괴적이지 않다"는 기준을 만족한다.
   * {@link AiCredentialService#KEY} 는 파괴적이진 않지만 그 자체로는 과금 주체를 바꿀 수 있는
   * 값이라 예외처럼 보인다 — 그 위험은 이 화이트리스트가 아니라 그 값의 범용 쓰기·단일 읽기를
   * 막는 {@code SettingsService.rejectBundleKey} 와, 유형별 검증·암호화를 전담하는
   * {@link AiCredentialService} 자체가 막는다(클래스 javadoc 참고).
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
          "ai.session_max_tokens",
          // 타입형 전환(2026-09). 옛 3키(ai.api_key/ai.cli_oauth_token/ai.agent_type)를 여기서
          // 빼고 이 하나로 합쳤다 — 테넌트별로 어떤 AI 를 쓰는가는 여전히 제품의 핵심 요구이고,
          // 과금 주체가 섞이지 않는 것은 이제 "문서가 하나라 재정의가 항상 통째"라는 구조 자체가
          // 보장한다(클래스 javadoc 참고). 값 자체의 범용 쓰기·단일 읽기는
          // SettingsService.rejectBundleKey 가 별도로 막는다.
          AiCredentialService.KEY,
          // P7-c1 재분류(2026-08-22). v1 은 "발신 도메인 신뢰도를 전 테넌트가 공유한다"를 근거로
          // 플랫폼 잠금이었으나, "자기 조직 명의로 메일을 보낸다"는 테넌트 요구가 앞선다고
          // 판단했다. embedding.model 과 결정적으로 다른 점: 값을 바꿔도 기존 데이터가
          // 무효화되지 않는다 — 파괴 버튼이 아니라 되돌릴 수 있는 설정이다.
          // 미설정 테넌트는 플랫폼 기본값(운영자가 편집)으로 폴백한다.
          "smtp.host",
          "smtp.port",
          "smtp.username",
          "smtp.password",
          "smtp.starttls",
          "smtp.from_address");

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
