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
  void 오버라이드_허용_키는_6개다() {
    assertThat(SettingsOverridePolicy.tenantOverridableKeys())
        .containsExactlyInAnyOrder(
            "ai.system_prompt", "ai.model", "ai.temperature",
            "ai.max_turns", "ai.max_tokens", "ai.session_max_tokens");
  }

  @Test
  void 플랫폼_잠금_키는_거부된다() {
    // 자격증명·임베딩·SMTP 는 전부 플랫폼 소유다. embedding.model 은 벡터 차원을 바꿔
    // 기존 임베딩 전량을 무효화하므로 특히 테넌트에게 줄 수 없다.
    assertThat(SettingsOverridePolicy.isTenantOverridable("ai.api_key")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("ai.agent_type")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("embedding.model")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("smtp.password")).isFalse();
  }

  @Test
  void 모르는_키는_잠금이_기본값이다() {
    // allow-list 이므로 장래에 추가되는 키는 명시적으로 허용될 때까지 플랫폼 잠금이다(fail-closed).
    assertThat(SettingsOverridePolicy.isTenantOverridable("ai.brand_new_key")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable(null)).isFalse();
  }
}
