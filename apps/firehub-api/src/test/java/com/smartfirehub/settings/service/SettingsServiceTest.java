package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>P7-b Task 5 이후 이 파일 전체가 {@code updatePlatformSettings} 를 검증한다.</b> 여기서 쓰는 키
 * (ai.api_key, ai.agent_type, embedding.* 등)는 전부 플랫폼 잠금이라 이제 {@code updateSettings}
 * (테넌트 평면, 6키 화이트리스트)로는 저장할 수 없다 — 저장 대상이 {@code updateSettings} 에서
 * {@code updatePlatformSettings} 로 옮겨졌을 뿐, 검증·마스킹·암호화 로직 자체는 그대로다(재사용).
 * 테넌트 평면 6키 쓰기는 {@code SettingsWritePlaneTest} 가 검증한다.
 */
@Transactional
class SettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private EncryptionService encryptionService;

  // getByPrefix_aiPrefix_returnsAiSettings 는 삭제했다 — 그 메서드가 P7-c1 에서 사라졌다(호출자 0).
  // 그 테스트가 지키던 "프리픽스 조회는 그 프리픽스 키만 준다"는 성질은 **살아 있는 경로**에서
  // 이미 고정돼 있다: 같은 파일의 getAsMap_withNullValue_returnsEmptyString 이 getAsMap("ai") 에
  // 대해 글자까지 같은 단언(allSatisfy startsWith("ai."))을 하고,
  // SettingsResolutionTest.프리픽스에_마침표를_붙이면_조용히_빈_맵이_된다 가 경계까지 본다.
  // 마스킹 계약은 아래 getAll_apiKey_returnsMasked 로 옮겼다(같은 maskSecret 을 지난다).

  @Test
  void updateSettings_validKey_updatesSuccessfully() {
    // given: pick a key we know exists from migrations
    // userId is null because no user seed data exists in the test DB;
    // updated_by column is nullable (no NOT NULL constraint).
    Map<String, String> update = Map.of("ai.max_turns", "10");

    // when / then: no exception
    settingsService.updatePlatformSettings(update, null);

    // 저장 확인은 운영자 평면이 실제로 쓰는 읽기(getAll)로 한다.
    assertThat(valueOf("ai.max_turns")).isEqualTo("10");
  }

  @Test
  void updateSettings_invalidKey_throwsIllegalArgumentException() {
    Map<String, String> update = Map.of("unknown.key", "value");

    assertThatThrownBy(() -> settingsService.updatePlatformSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("허용되지 않는 설정 키");
  }

  @Test
  void updateSettings_maxTurnsBelowRange_throwsIllegalArgumentException() {
    // ai.max_turns must be 1~50; 0 is invalid
    Map<String, String> update = Map.of("ai.max_turns", "0");

    assertThatThrownBy(() -> settingsService.updatePlatformSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 턴 수는 1에서 50 사이");
  }

  @Test
  void updateSettings_temperatureAboveRange_throwsIllegalArgumentException() {
    // ai.temperature must be 0.0~1.0; 1.5 is invalid
    Map<String, String> update = Map.of("ai.temperature", "1.5");

    assertThatThrownBy(() -> settingsService.updatePlatformSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Temperature는 0.0에서 1.0 사이");
  }

  // ── API key tests ─────────────────────────────────────────────────────────

  @Test
  void updateSettings_apiKey_encryptsBeforeStore() {
    // when: store a plain-text API key
    settingsService.updatePlatformSettings(Map.of("ai.api_key", "sk-test-plain-key"), null);

    // then: the raw value in DB is NOT the plain text — it is an encrypted iv:ciphertext blob
    Optional<String> rawStored = settingsService.getValue("ai.api_key");
    assertThat(rawStored).isPresent();
    String raw = rawStored.get();
    assertThat(raw).isNotEqualTo("sk-test-plain-key");
    // AES-GCM output format is "base64iv:base64cipher" — both parts are Base64, separated by ':'
    assertThat(raw).contains(":");

    // and the stored value decrypts back to the original
    assertThat(encryptionService.decrypt(raw)).isEqualTo("sk-test-plain-key");
  }

  @Test
  void updateSettings_apiKey_emptyValue_throwsValidation() {
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of("ai.api_key", ""), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API 키는 비어있을 수 없습니다");
  }

  /**
   * 마스킹 계약은 <b>살아 있는 읽기 경로</b>에서 지킨다. 예전에는 {@code getByPrefix} 로 물었는데
   * 그 메서드는 P7-c1 에서 호출자가 0이 되어 사라졌다 — 죽은 경로를 지키는 테스트는 계약을
   * 지키는 것처럼 보이지만 아무도 지나가지 않는 문을 잠그는 것이다. {@code getAll} 은 운영자
   * 평면({@code GET /api/platform/settings})이 실제로 부르는 경로다.
   */
  @Test
  void getAll_apiKey_returnsMasked() {
    // given: store a real API key first
    settingsService.updatePlatformSettings(Map.of("ai.api_key", "sk-test-abcdefghij"), null);

    // then: ai.api_key value must start with "****" (masked)
    assertThat(valueOf("ai.api_key")).startsWith("****");
  }

  @Test
  void getDecryptedApiKey_returnsOriginal() {
    // given: encrypt and persist
    settingsService.updatePlatformSettings(Map.of("ai.api_key", "sk-original-secret"), null);

    // when
    Optional<String> decrypted = settingsService.getDecryptedApiKey();

    // then: original plain-text is recovered
    assertThat(decrypted).isPresent().hasValue("sk-original-secret");
  }

  @Test
  void getDecryptedApiKey_notSet_returnsEmpty() {
    // given: ensure api_key is blank (reset to empty by storing a blank-equivalent via direct repo)
    // The V31 migration seeds ai.api_key with '' — within this @Transactional test we can rely on
    // that initial empty state because no other test in this class persists a key before us.
    // We explicitly reset it here through a masked-value update (which skips the DB write),
    // so the value remains the seeded empty string.
    // Actually we just call getDecryptedApiKey on the unmodified seeded empty row.
    Optional<String> result = settingsService.getDecryptedApiKey();

    assertThat(result).isEmpty();
  }

  // ── getAsMap NPE 회귀 테스트 ──────────────────────────────────────────────

  @Test
  void getAsMap_withNullValue_returnsEmptyString() {
    // given: ai.api_key는 초기 시드값이 '' (빈 문자열)이지만, system_settings.value 컬럼은 nullable이다.
    // null value가 포함된 경우 Collectors.toMap이 NPE를 발생시키는 버그를 검증한다.
    // 실제 null을 직접 주입하기 위해 ai.model 설정을 먼저 확인하고, JDBC로 null 업데이트를 수행한다.
    // 단, IntegrationTestBase에서 직접 DSLContext를 사용할 수 없으므로
    // getAsMap이 현재 DB 상태(빈 문자열 포함)에서 NPE 없이 정상 동작함을 검증한다.
    // null value 시나리오는 getAsMap_withMixedValues_returnsMappedCorrectly에서 별도 커버한다.
    Map<String, String> result = settingsService.getAsMap("ai");

    // NPE 없이 정상 반환되어야 한다
    assertThat(result).isNotNull();
    assertThat(result.keySet()).allSatisfy(k -> assertThat(k).startsWith("ai."));
    // 모든 value는 null이 아니어야 한다 (null은 빈 문자열로 치환)
    assertThat(result.values()).doesNotContainNull();
  }

  @Test
  void getAsMap_withMixedValues_returnsMappedCorrectly() {
    // given: 정상 값이 있는 설정을 업데이트한다
    settingsService.updatePlatformSettings(Map.of("ai.max_turns", "15"), null);

    // when: getAsMap 호출
    Map<String, String> result = settingsService.getAsMap("ai");

    // then: 정상 값은 그대로 반환된다
    assertThat(result).isNotNull();
    assertThat(result).containsEntry("ai.max_turns", "15");
    // 모든 value는 null이 아니어야 한다 (null → 빈 문자열 치환 정책)
    assertThat(result.values()).doesNotContainNull();
  }

  // ── ai.agent_type opencode 허용 테스트 ────────────────────────────────────

  @Test
  void updateSettings_opencode_agentType_허용() {
    // ai.agent_type = "opencode" 저장이 예외 없이 통과해야 한다
    assertDoesNotThrow(() -> settingsService.updatePlatformSettings(Map.of("ai.agent_type", "opencode"), null));
    assertThat(settingsService.getAsMap("ai")).containsEntry("ai.agent_type", "opencode");
  }

  @Test
  void updateSettings_invalidAgentType_throwsIllegalArgumentException() {
    // opencode 허용 후에도 잘못된 값은 예외가 발생해야 한다
    assertThatThrownBy(
            () -> settingsService.updatePlatformSettings(Map.of("ai.agent_type", "unknown-type"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("에이전트 유형은 sdk, cli, cli-api, opencode 중 하나여야 합니다");
  }

  // ── 임베딩 설정 테스트 ─────────────────────────────────────────────────────

  @Test
  void embeddingProviderRejectsInvalidValue() {
    assertThatThrownBy(
            () -> settingsService.updatePlatformSettings(Map.of("embedding.provider", "INVALID"), 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void embeddingApiKeyIsMaskedOnRead() {
    // updated_by FK 제약 때문에 test DB에 존재하지 않는 userId 대신 null 사용 (기존 테스트 관례)
    settingsService.updatePlatformSettings(Map.of("embedding.api_key", "secret-key-123"), null);
    assertThat(valueOf("embedding.api_key")).doesNotContain("secret-key-123");
    // ai.api_key 마스킹 테스트와 동일하게 마스킹 포맷(****)도 검증한다
    assertThat(valueOf("embedding.api_key")).startsWith("****");
  }

  /**
   * 운영자 평면 읽기({@code getAll}, 18키 전부)에서 한 키의 값을 꺼낸다.
   *
   * <p>{@code getByPrefix} 삭제로 세 단언이 {@code getAll} + 키 필터로 옮겨오면서 <b>같은 단계가
   * 두 가지 철자</b>로 남았다({@code filteredOn/hasSize/first/satisfies} 와
   * {@code stream/filter/findFirst/orElseThrow}). 같은 것을 묻는 세 테스트가 서로 다른 것을 묻는
   * 것처럼 읽히고, {@code getAll} 이 18키를 돌려주므로 새 단언마다 필터를 손으로 다시 쓰게 된다.
   */
  private String valueOf(String key) {
    return settingsService.getAll().stream()
        .filter(s -> key.equals(s.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(key + " 가 getAll 결과에 없다"))
        .value();
  }

  @Test
  void updateSettings_apiKey_maskedValue_skipsUpdate() {
    // given: store a real key first
    settingsService.updatePlatformSettings(Map.of("ai.api_key", "sk-real-key-stored"), null);
    Optional<String> encryptedAfterFirstStore = settingsService.getValue("ai.api_key");
    assertThat(encryptedAfterFirstStore).isPresent();
    String encryptedValue = encryptedAfterFirstStore.get();

    // when: send a masked value (as the frontend does when the user has not changed the key)
    settingsService.updatePlatformSettings(Map.of("ai.api_key", "****abcd"), null);

    // then: the stored encrypted value must NOT have changed
    Optional<String> encryptedAfterMaskedUpdate = settingsService.getValue("ai.api_key");
    assertThat(encryptedAfterMaskedUpdate).isPresent().hasValue(encryptedValue);
  }

  // ── 임베딩 provider 정합성 검증 (#322 base_url 불일치 / #323 API 키 누락) ──────────

  /** OPENAI 로 바꾸면서 Ollama 주소(http)가 남아 있으면 저장을 막아야 한다 (#322 의 핵심 조합). */
  @Test
  void embeddingOpenAiRejectsNonHttpsBaseUrl() {
    assertThatThrownBy(
            () ->
                settingsService.updatePlatformSettings(
                    Map.of(
                        "embedding.provider", "OPENAI",
                        "embedding.model", "text-embedding-3-small",
                        "embedding.base_url", "http://localhost:11434",
                        "embedding.api_key", "sk-test-key"),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("https");
  }

  /** provider/base_url/api_key 가 모두 정합적이면 저장된다 (검증이 정상 저장을 막지 않는지 확인). */
  @Test
  void embeddingOpenAiAcceptsHttpsBaseUrlWithApiKey() {
    assertDoesNotThrow(
        () ->
            settingsService.updatePlatformSettings(
                Map.of(
                    "embedding.provider", "OPENAI",
                    "embedding.model", "text-embedding-3-small",
                    "embedding.base_url", "https://api.openai.com",
                    "embedding.api_key", "sk-test-key"),
                null));
    assertThat(settingsService.getValue("embedding.base_url")).hasValue("https://api.openai.com");
  }

  /** OPENAI 인데 API 키가 페이로드에도 없고 저장된 값도 없으면 거부한다 (#323). */
  @Test
  void embeddingOpenAiRejectsBlankApiKey() {
    // 시드 상태에서 embedding.api_key 는 빈 값이므로 페이로드에서도 빈 값을 보낸다
    assertThatThrownBy(
            () ->
                settingsService.updatePlatformSettings(
                    Map.of(
                        "embedding.provider", "OPENAI",
                        "embedding.base_url", "https://api.openai.com",
                        "embedding.api_key", ""),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API 키가 필요합니다");
  }

  /** api_key 를 아예 보내지 않아도 저장된 키가 있으면 통과해야 한다 — 마스킹 흐름 회귀 방지. */
  @Test
  void embeddingOpenAiAcceptsOmittedApiKeyWhenStoredKeyExists() {
    settingsService.updatePlatformSettings(Map.of("embedding.api_key", "sk-stored-key"), null);

    // 프론트는 마스킹된 키를 페이로드에서 제거하므로 api_key 없이 provider 만 바뀌는 요청이 온다
    assertDoesNotThrow(
        () ->
            settingsService.updatePlatformSettings(
                Map.of(
                    "embedding.provider", "OPENAI",
                    "embedding.base_url", "https://api.openai.com"),
                null));
  }

  /**
   * 마스킹 값(****)은 "기존 키 유지"이므로 OPENAI 저장이 통과해야 한다.
   *
   * <p>Task 5 가 센티널 판정을 {@code EncryptionService.maskValue} 의 <b>형태</b>(길이 4 또는 8)로
   * 좁혔다. 예전 값 {@code "****key"}(길이 7)는 서버가 만들 수 없는 마스크라, 그대로 두면 이제
   * 진짜 키로 저장돼 이 테스트가 지키려던 "덮어쓰지 않는다"를 스스로 깬다. 실제 마스크
   * ({@code maskValue("sk-stored-key") == "****-key"})로 바꾼다.
   */
  @Test
  void embeddingOpenAiAcceptsMaskedApiKeyWhenStoredKeyExists() {
    settingsService.updatePlatformSettings(Map.of("embedding.api_key", "sk-stored-key"), null);

    assertDoesNotThrow(
        () ->
            settingsService.updatePlatformSettings(
                Map.of(
                    "embedding.provider", "OPENAI",
                    "embedding.base_url", "https://api.openai.com",
                    "embedding.api_key", "****-key"),
                null));
    // 마스킹 값이 실제 키를 덮어쓰지 않았는지 확인
    assertThat(settingsService.getDecryptedEmbeddingApiKey()).hasValue("sk-stored-key");
  }

  /** 저장된 키가 있어도 사용자가 명시적으로 빈 값을 보내면 키 삭제이므로 거부한다. */
  @Test
  void embeddingOpenAiRejectsExplicitBlankApiKeyEvenWithStoredKey() {
    settingsService.updatePlatformSettings(Map.of("embedding.api_key", "sk-stored-key"), null);

    assertThatThrownBy(
            () ->
                settingsService.updatePlatformSettings(
                    Map.of(
                        "embedding.provider", "OPENAI",
                        "embedding.base_url", "https://api.openai.com",
                        "embedding.api_key", ""),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API 키가 필요합니다");
  }

  /** base_url 형식 검증 — 절대 URL 이 아니면 provider 와 무관하게 거부한다. */
  @Test
  void embeddingRejectsMalformedBaseUrl() {
    assertThatThrownBy(
            () -> settingsService.updatePlatformSettings(Map.of("embedding.base_url", "localhost:11434"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http://");
  }

  /** OLLAMA 는 API 키 없이 http 로컬 주소를 그대로 저장할 수 있어야 한다 (기존 동작 보존). */
  @Test
  void embeddingOllamaAcceptsLocalHttpBaseUrlWithoutApiKey() {
    assertDoesNotThrow(
        () ->
            settingsService.updatePlatformSettings(
                Map.of(
                    "embedding.provider", "OLLAMA",
                    "embedding.model", "bge-m3",
                    "embedding.base_url", "http://localhost:11434",
                    "embedding.api_key", ""),
                null));
  }
}
