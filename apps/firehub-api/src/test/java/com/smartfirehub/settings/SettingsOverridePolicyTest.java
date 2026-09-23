package com.smartfirehub.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.service.SettingsOverridePolicy;
import com.smartfirehub.settings.service.SettingsOverridePolicy.Plane;
import org.junit.jupiter.api.Test;

/**
 * {@link SettingsOverridePolicy} allow-list 단위 테스트. DB/Spring 컨텍스트가 필요 없는 순수
 * 단위 테스트라 {@code IntegrationTestBase} 를 상속하지 않는다.
 */
class SettingsOverridePolicyTest {

  @Test
  void 테넌트_쓰기_허용_키는_12개다() {
    // 테넌트 전용 ai.* 동작 6키 + 테넌트 전용 smtp.* 6키(#712).
    assertThat(SettingsOverridePolicy.tenantOverridableKeys())
        .containsExactlyInAnyOrder(
            "ai.system_prompt", "ai.model", "ai.temperature",
            "ai.max_turns", "ai.max_tokens", "ai.session_max_tokens",
            "smtp.host", "smtp.port", "smtp.username",
            "smtp.password", "smtp.starttls", "smtp.from_address");
  }

  @Test
  void AI_동작_키와_SMTP_키는_테넌트_전용이다() {
    // AI 설정은 플랫폼 기본값이 없다 — 테넌트 값이 없으면 코드 기본값이다(#706 후속).
    for (String key :
        java.util.List.of(
            "ai.system_prompt", "ai.model", "ai.temperature",
            "ai.max_turns", "ai.max_tokens", "ai.session_max_tokens")) {
      assertThat(SettingsOverridePolicy.planeOf(key)).as(key).isEqualTo(Plane.TENANT_ONLY);
      assertThat(SettingsOverridePolicy.smtpKeys()).as(key).doesNotContain(key);
    }
    // SMTP 도 플랫폼 행을 읽지 않는다 — 테넌트 값이 없으면 미설정이다(#712).
    assertThat(SettingsOverridePolicy.smtpKeys())
        .containsExactlyInAnyOrder(
            "smtp.host", "smtp.port", "smtp.username",
            "smtp.password", "smtp.starttls", "smtp.from_address");
    for (String key : SettingsOverridePolicy.smtpKeys()) {
      assertThat(SettingsOverridePolicy.planeOf(key)).as(key).isEqualTo(Plane.TENANT_ONLY);
      assertThat(SettingsOverridePolicy.planeOf(key).readsPlatformRow()).as(key).isFalse();
    }
    // 목록에 없는 smtp.* 도 네임스페이스로 테넌트 전용이다 — 옛 플랫폼 행이 새지 않는다.
    assertThat(SettingsOverridePolicy.planeOf("smtp.legacy_key")).isEqualTo(Plane.TENANT_ONLY);
    assertThat(SettingsOverridePolicy.isTenantOverridable("smtp.legacy_key")).isFalse();
  }

  @Test
  void 평면_분류() {
    // ai.credential 은 AiCredentialService 소유, 옛 ai.* 키도 플랫폼 행을 읽지 않는다.
    assertThat(SettingsOverridePolicy.planeOf("ai.credential")).isEqualTo(Plane.EXTERNAL_OWNER);
    assertThat(SettingsOverridePolicy.planeOf("ai.agent_type")).isEqualTo(Plane.TENANT_ONLY);
    assertThat(SettingsOverridePolicy.planeOf("embedding.model")).isEqualTo(Plane.PLATFORM_ONLY);
    assertThat(SettingsOverridePolicy.planeOf("unknown.key")).isEqualTo(Plane.UNKNOWN);
    assertThat(SettingsOverridePolicy.planeOf(null)).isEqualTo(Plane.UNKNOWN);
    assertThat(SettingsOverridePolicy.mayHavePlatformRows("ai")).isFalse();
    assertThat(SettingsOverridePolicy.mayHavePlatformRows("smtp")).isFalse();
    assertThat(SettingsOverridePolicy.mayHavePlatformRows("embedding")).isTrue();
  }

  @Test
  void AI_자격증명은_두_평면_키가_아니다() {
    // #706 결정 7 — AiCredentialService 전용 값이라 이 화이트리스트에 없다. 옛 평면 3키도 마찬가지다.
    // (AiCredentialService.KEY 는 다른 패키지의 package-private 상수라 리터럴을 쓴다.)
    for (String key : java.util.List.of("ai.credential", "ai.api_key", "ai.cli_oauth_token", "ai.agent_type")) {
      assertThat(SettingsOverridePolicy.isTenantOverridable(key)).as(key).isFalse();
    }
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

  @Test
  void 분류_전용_두_키는_전용_서비스_소유이고_범용_쓰기_대상이_아니다() {
    // ai.classify_model 이 "그 밖의 ai.* → TENANT_ONLY" 에 떨어지면 범용 경로가 묶음 한쪽만
    // 건드릴 수 있게 된다 — 두 키는 AiCredentialService 만 함께 쓰고 함께 지운다(#707).
    for (String key : java.util.List.of("ai.classify_credential", "ai.classify_model")) {
      assertThat(SettingsOverridePolicy.planeOf(key)).as(key).isEqualTo(Plane.EXTERNAL_OWNER);
      assertThat(SettingsOverridePolicy.isTenantOverridable(key)).as(key).isFalse();
    }
  }
}
