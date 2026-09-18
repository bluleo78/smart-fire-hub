package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code SettingsService.applyAiCredentialBundle} 순수 단위 테스트. Spring 컨텍스트도 DB 도 없이 3키 원자
 * 해석 규칙만 고정한다.
 *
 * <p><b>왜 이 패키지인가.</b> {@code applyAiCredentialBundle} 은 {@link SettingsKeyWhitelistInvariantTest}
 * 와 같은 이유로 패키지 가시성이다 — 테스트 하나를 위해 {@code SettingsService} 의 내부 메서드를
 * {@code public} 으로 여는 것보다, 테스트를 그 상수·메서드가 사는 {@code com.smartfirehub.settings.service}
 * 패키지로 옮기는 쪽이 노출이 작다.
 *
 * <p><b>왜 {@code SettingsResolutionTest}(통합 테스트)가 아닌가.</b> 이 시점에는 {@code ai.api_key}/
 * {@code ai.cli_oauth_token}/{@code ai.agent_type} 셋 다 {@code SettingsOverridePolicy.TENANT_OVERRIDABLE}
 * 에 없다 — Task 3 이 그 화이트리스트를 연다. {@code resolveOverridesByPrefix} 의 {@code removeIf} 가
 * 오버라이드 후보에서 이 3키를 먼저 걸러내므로, DB 를 거쳐 {@code getAsMap("ai")} 를 부르는 통합
 * 테스트로는 번들 로직 자체를 검증할 수 없다(항상 "행이 애초에 없었던 것"과 구분 불가). 그래서 이
 * 메서드를 직접 호출하는 순수 단위 테스트로 규칙을 고정하고, 화이트리스트가 열리는 Task 3 에서
 * {@code SettingsResolutionTest} 가 end-to-end 로 같은 규칙을 다시 확인한다.
 */
class AiCredentialBundleTest {

  /**
   * 이 태스크가 막는 과금 유출의 핵심 성질. {@code ai.agent_type} 만 저장된 상태를 그대로 두면
   * {@code ai.api_key} 는 상위 {@code putAll} 에서 플랫폼 암호문으로 상속된다 — 테넌트가 고른
   * 실행 형태에 플랫폼이 비용을 낸다. 번들이 두 자격증명 키를 빈 문자열로 채워야 그 상속이
   * 끊긴다.
   */
  @Test
  void agent_type만_있으면_api_key와_cli_oauth_token은_플랫폼으로_새지_않고_빈값이_된다() {
    Map<String, String> overrides = new LinkedHashMap<>(Map.of("ai.agent_type", "sdk"));

    SettingsService.applyAiCredentialBundle(overrides);

    assertThat(overrides).containsEntry("ai.agent_type", "sdk");
    assertThat(overrides)
        .as("플랫폼 API 키가 테넌트가 고른 실행 형태와 섞이면 안 된다")
        .containsEntry("ai.api_key", "");
    assertThat(overrides).containsEntry("ai.cli_oauth_token", "");
  }

  @Test
  void api_key만_있으면_agent_type은_sdk로_채워지고_cli_oauth_token은_빈값이다() {
    Map<String, String> overrides = new LinkedHashMap<>(Map.of("ai.api_key", "tenant-key"));

    SettingsService.applyAiCredentialBundle(overrides);

    assertThat(overrides).containsEntry("ai.api_key", "tenant-key");
    assertThat(overrides).containsEntry("ai.agent_type", "sdk");
    assertThat(overrides).containsEntry("ai.cli_oauth_token", "");
  }

  /**
   * 번들은 3키 중 하나라도 있을 때만 발동한다. 관련 없는 {@code ai.*} 키(예: {@code ai.model})만
   * 있는 맵은 건드리지 않는다 — 트리거가 "{@code ai.} 접두사"가 아니라 정확히 이 3키임을 증명한다.
   * 미설정 테넌트의 동작이 바뀌면 안 된다.
   */
  @Test
  void 세키_모두_없으면_맵은_전혀_변하지_않는다() {
    Map<String, String> overrides = new LinkedHashMap<>(Map.of("ai.model", "gpt-5"));
    Map<String, String> before = new LinkedHashMap<>(overrides);

    SettingsService.applyAiCredentialBundle(overrides);

    assertThat(overrides).isEqualTo(before);
  }

  /**
   * 채움은 {@code putIfAbsent} 다 — 이미 저장된 값을 덮어쓰면 테넌트가 명시적으로 넣은 값이
   * 사라진다.
   */
  @Test
  void 이미_있는_값은_번들_채움이_덮어쓰지_않는다() {
    Map<String, String> overrides =
        new LinkedHashMap<>(
            Map.of(
                "ai.agent_type", "cli",
                "ai.api_key", "already-set",
                "ai.cli_oauth_token", "already-set-token"));

    SettingsService.applyAiCredentialBundle(overrides);

    assertThat(overrides).containsEntry("ai.agent_type", "cli");
    assertThat(overrides).containsEntry("ai.api_key", "already-set");
    assertThat(overrides).containsEntry("ai.cli_oauth_token", "already-set-token");
  }
}
