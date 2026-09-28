package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테넌트 쓰기 경로의 값 검증·getAsMap NPE 회귀를 본다. 플랫폼 쓰기(updatePlatformSettings)는 #713 에서
 * 사라졌다.
 */
@Transactional
class SettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;

  // getByPrefix_aiPrefix_returnsAiSettings 는 삭제했다 — 그 메서드가 P7-c1 에서 사라졌다(호출자 0).
  // 그 테스트가 지키던 "프리픽스 조회는 그 프리픽스 키만 준다"는 성질은 **살아 있는 경로**에서
  // 이미 고정돼 있다: 같은 파일의 getAsMap_withNullValue_returnsEmptyString 이 getAsMap("ai") 에
  // 대해 글자까지 같은 단언(allSatisfy startsWith("ai."))을 하고,
  // SettingsResolutionTest.프리픽스에_마침표를_붙이면_조용히_빈_맵이_된다 가 경계까지 본다.

  @Test
  void updateSettings_invalidKey_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("unknown.key", "value"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("플랫폼 관리자만 변경할 수 있는 설정입니다: unknown.key");
  }

  // AI 값 범위 검증은 테넌트 쓰기 경로에서 한다(AI 설정은 테넌트 전용). validateValues 는 저장
  // 전에 던지므로 tenant_settings 에 아무것도 쓰이지 않는다.
  @Test
  void updateSettings_maxTurnsBelowRange_throwsIllegalArgumentException() {
    // ai.max_turns must be 1~50; 0 is invalid
    Map<String, String> update = Map.of("ai.max_turns", "0");

    assertThatThrownBy(() -> settingsService.updateSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 턴 수는 1에서 50 사이");
  }

  @Test
  void updateSettings_temperatureAboveRange_throwsIllegalArgumentException() {
    // ai.temperature must be 0.0~1.0; 1.5 is invalid
    Map<String, String> update = Map.of("ai.temperature", "1.5");

    assertThatThrownBy(() -> settingsService.updateSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Temperature는 0.0에서 1.0 사이");
  }

  // getAiCredentials_apiKey_returnsOriginal / getAiCredentials_apiKey_notSet_returnsEmpty 는
  // 지웠다 — 검증 대상이던 SettingsService.getAiCredentials()/AiCredentials 자체가 타입형 전환
  // (2026-09)으로 사라졌다. 복호화된 원문 복원 계약은 지금은 AiCredentialService.resolve()/
  // AiCredentialServiceTest 가 진다.

  // ── getAsMap NPE 회귀 테스트 ──────────────────────────────────────────────

  @Test
  void getAsMap_withNullValue_returnsEmptyString() {
    // given: system_settings.value 컬럼은 nullable이다.
    // null value가 포함된 경우 Collectors.toMap이 NPE를 발생시키는 버그를 검증한다.
    // getAsMap이 현재 DB 상태(빈 문자열 포함)에서 NPE 없이 정상 동작함을 검증한다.
    Map<String, String> result = settingsService.getAsMap("ai");

    // NPE 없이 정상 반환되어야 한다
    assertThat(result).isNotNull();
    assertThat(result.keySet()).allSatisfy(k -> assertThat(k).startsWith("ai."));
    // 모든 value는 null이 아니어야 한다 (null은 빈 문자열로 치환)
    assertThat(result.values()).doesNotContainNull();
  }

  // ai.agent_type 값 검증 테스트(opencode 허용/알 수 없는 값 거부)는 지웠다 — 그 키 자체가 #706 으로
  // 화이트리스트에서 빠졌다. 유형 검증은 AiCredentialService.save 의 KNOWN_AGENT_TYPES 가 진다
  // (AiCredentialServiceTest).

  // 임베딩 설정 검증(embeddingProviderRejectsInvalidValue 등)은 #713 에서 EmbeddingConfigService·
  // EmbeddingTargetGuardTest·EmbeddingSettingsServiceTest 로 옮겨졌다. 플랫폼 쓰기 경로
  // (updatePlatformSettings) 자체가 사라졌으므로 여기 남길 것이 없다.
}
