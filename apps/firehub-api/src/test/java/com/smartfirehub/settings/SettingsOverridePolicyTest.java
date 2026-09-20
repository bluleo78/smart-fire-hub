package com.smartfirehub.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.service.SettingsOverridePolicy;
import org.junit.jupiter.api.Test;

/**
 * {@link SettingsOverridePolicy} allow-list 단위 테스트. DB/Spring 컨텍스트가 필요 없는 순수
 * 단위 테스트라 {@code IntegrationTestBase} 를 상속하지 않는다.
 */
class SettingsOverridePolicyTest {

  @Test
  void 오버라이드_허용_키는_13개다() {
    // P7-c1(2026-08-22): smtp.* 6키 재분류. 타입형 전환(2026-09): ai 자격증명 3키
    // (ai.api_key/ai.cli_oauth_token/ai.agent_type) 를 빼고 ai.credential 하나로 합쳤다
    // (9 - 3 + 1 = 7개 ai.* + 6개 smtp.* = 13개).
    assertThat(SettingsOverridePolicy.tenantOverridableKeys())
        .containsExactlyInAnyOrder(
            "ai.system_prompt", "ai.model", "ai.temperature",
            "ai.max_turns", "ai.max_tokens", "ai.session_max_tokens",
            // AiCredentialService.KEY 는 다른 패키지(settings.service) package-private 상수라
            // 이 테스트(settings 패키지)에서 참조할 수 없다 — 리터럴을 쓴다.
            "ai.credential",
            "smtp.host", "smtp.port", "smtp.username",
            "smtp.password", "smtp.starttls", "smtp.from_address");
  }

  @Test
  void 플랫폼_잠금_키는_거부된다() {
    // 이제 플랫폼 잠금은 embedding.* 4키뿐이다. embedding.model 은 벡터 차원을 바꿔 기존
    // 임베딩을 무효화하므로 Phase B 가 차원별 컬럼을 넣을 때까지 잠겨 있다.
    assertThat(SettingsOverridePolicy.isTenantOverridable("embedding.model")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("embedding.api_key")).isFalse();
  }

  @Test
  void 모르는_키는_잠금이_기본값이다() {
    // allow-list 이므로 장래에 추가되는 키는 명시적으로 허용될 때까지 플랫폼 잠금이다(fail-closed).
    assertThat(SettingsOverridePolicy.isTenantOverridable("ai.brand_new_key")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable(null)).isFalse();
  }
}
