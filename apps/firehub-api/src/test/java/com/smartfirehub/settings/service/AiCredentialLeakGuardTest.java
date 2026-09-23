package com.smartfirehub.settings.service;

import static com.smartfirehub.settings.service.AiCredentialService.KEY;
import static com.smartfirehub.support.SettingsTestSupport.upsertSystemSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SettingsTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code ai.credential} 이 범용 설정 경로로 유출·우회되지 않는지 고정한다(Task 3).
 *
 * <p>이 키는 {@link SettingsService#SECRET_KEYS} 의 원소가 아니다(비밀이 최상위 값이 아니라
 * 하위 필드에 있다) — 아무 조치도 하지 않으면 {@code getAll}/{@code getResolvedByPrefix}/
 * {@code getAsMap} 이 이 JSON 값을(비밀 하위 필드의 AES 암호문까지) 그대로 내보낸다. 이 키는
 * 테넌트 전용 값(#706)이라 {@code tenant_settings} 행으로 실재하고, V126 이전 DB 에는
 * {@code system_settings} 행도 남아 있을 수 있다 — 두 저장소 모두에 시드해 두고 본다.
 *
 * <p><b>정리 방식은 {@code AiCredentialServiceTest} 와 같다</b>(플랫폼 행 캡처·원복, 테넌트 행
 * 삭제) — 공유 테스트 DB 에 이 클래스가 심은 {@code ai.credential} 이 남으면 기본 테넌트(1번)를
 * 쓰는 다른 테스트의 {@code resolve()} 가 영향을 받는다.
 */
class AiCredentialLeakGuardTest extends IntegrationTestBase {

  private static final Long USER = null;

  @Autowired private SettingsService settingsService;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;

  private String platformOriginal;

  @BeforeEach
  void seed() {
    platformOriginal = SettingsTestSupport.rawSystemSettingValue(dsl, KEY);
    // 옛 플랫폼 행(V126 이 지우기 전의 DB, 혹은 손으로 되살린 행)을 흉내낸다 — 쓰기 API 가 없으므로
    // 직접 심는다. 범용 읽기 경로가 이 행을 그대로 흘리면 안 된다.
    upsertSystemSetting(
        dsl,
        KEY,
        "{\"v\":1,\"agentType\":\"sdk\",\"payload\":{},\"secret\":{\"apiKey\":\"sk-live-secret\"}}");

    // 시드가 실제로 반영됐는지 먼저 확인한다 — 확인하지 않으면 "행이 애초에 없어서 유출도
    // 없다"는 이유로 아래 모든 유출 테스트가 공허하게 통과할 수 있다.
    String raw = SettingsTestSupport.rawSystemSettingValue(dsl, KEY);
    assertThat(raw).as("시드가 반영되지 않으면 아래 테스트 전부가 공허해진다").contains("agentType");
  }

  @AfterEach
  void cleanup() {
    // AiCredentialServiceTest 의 @AfterEach 와 같은 순서(테넌트 행 삭제 → 플랫폼 행 원복) —
    // IntegrationTestBase.clearTenantContext() 보다 먼저 실행돼야 TenantContext.require() 가 통과한다.
    tenantSettingsRepository.delete(KEY);
    // 분류 전용 묶음도 지운다 — 공유 test DB 의 기본 테넌트에 남으면 다른 테스트의 분류 해석이
    // "분류 전용 설정됨"으로 뒤집힌다.
    tenantSettingsRepository.delete(AiCredentialSlot.CLASSIFY.key());
    tenantSettingsRepository.delete(AiCredentialSlot.CLASSIFY_MODEL_KEY);
    SettingsTestSupport.restoreSystemSettingValue(dsl, KEY, platformOriginal);
  }

  @Test
  void getAll_은_ai_credential_을_내보내지_않는다() {
    assertThat(settingsService.getAll()).extracting(SettingResponse::key).doesNotContain(KEY);
  }

  @Test
  void prefix_조회는_ai_credential_을_내보내지_않는다() {
    assertThat(settingsService.getResolvedByPrefix("ai"))
        .extracting(ResolvedSettingResponse::key)
        .doesNotContain(KEY);
    assertThat(settingsService.getAsMap("ai")).doesNotContainKey(KEY);
  }

  /**
   * 플랫폼 시드만으로는 잡지 못하는 유출 경로: {@code ai.credential} 은 테넌트 전용 값이라
   * {@code tenant_settings} 행으로 실재한다. {@code resolveOverridesByPrefix} 가 이 키를 걸러
   * 내지 않으면(화이트리스트 제외 + 방어적 명시 제외 둘 다 사라지면), 플랫폼 스트림만 거른 구현도
   * 이 테스트에서는 유출이 드러난다 — {@code getResolvedByPrefix}/{@code getAsMap} 이 오버라이드
   * 맵을 그대로 {@code putAll} 하기 때문이다.
   */
  @Test
  void 테넌트_행으로_저장해도_prefix_조회는_ai_credential_을_내보내지_않는다() {
    aiCredentialService.save(
        new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "sk-tenant-secret")), USER);
    String tenantRaw = tenantSettingsRepository.findValue(KEY).orElseThrow();
    assertThat(tenantRaw).as("테넌트 시드가 반영되지 않으면 아래 단언이 공허해진다").contains("agentType");

    assertThat(settingsService.getResolvedByPrefix("ai"))
        .extracting(ResolvedSettingResponse::key)
        .doesNotContain(KEY);
    assertThat(settingsService.getAsMap("ai")).doesNotContainKey(KEY);
  }

  @Test
  void 단건_조회는_거부된다() {
    assertThatThrownBy(() -> settingsService.getValue(KEY)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * #706 결정 7 — {@code ai.credential} 이 테넌트 오버라이드 화이트리스트에서 빠진 뒤에도 범용 PUT
   * 경로({@code updateSettings})는 이 키를 계속 <b>거부</b>해야 한다. 새면 {@code AiCredentialService}
   * 의 비밀 필수 규칙을 우회해 비밀 없는 문서를 저장할 수 있다. 거부 문구로 전용 관문
   * ({@code rejectExternalOwnerKey})이 먼저 막는지까지 본다 — 화이트리스트 거부("플랫폼 관리자만")로
   * 떨어지면 그 관문이 사라진 것이다.
   */
  @Test
  void 범용_쓰기로는_테넌트_평면에_저장할_수_없다() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of(KEY, "{}"), USER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AI 자격증명은 범용 설정 경로로");
    assertThat(tenantSettingsRepository.findValue(KEY)).isEmpty();
  }

  /**
   * #706 — 전용 {@code DELETE /settings/ai-credential} 을 없앤 뒤, 범용 오버라이드 삭제
   * ({@code DELETE /settings/overrides/ai.credential} → {@code clearOverride})가 그 뒷문이 되면 안
   * 된다. 테넌트 행을 실제로 만들어 두고, 거부된 뒤에도 행이 남아 있는지 본다(행이 없으면 "삭제할
   * 게 없어서 무동작"과 구분되지 않는다).
   */
  @Test
  void 범용_오버라이드_삭제로는_ai_credential_을_지울_수_없다() {
    aiCredentialService.save(
        new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "sk-tenant-secret")), USER);

    assertThatThrownBy(() -> settingsService.clearOverride(KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tenantSettingsRepository.findValue(KEY)).isPresent();
  }

  /**
   * {@code updatePlatformSettings} 는 {@code ai.*} 키 전부를 거부한다(AI 설정은 테넌트 전용) —
   * 이 키도 그 검사에 걸린다. 범용 쓰기로 플랫폼 평면에 이 키를 저장할 수 없다는 계약을 실행 가능한
   * 단언으로 고정해 둔다.
   */
  @Test
  void 범용_쓰기로는_플랫폼_평면에도_저장할_수_없다() {
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of(KEY, "{}"), USER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 위 테스트들이 키 이름만 본다면 이 테스트는 값 전체를 본다 — 나중에 키 이름이 바뀌어도
   * 유출이 잡히게 하는 마지막 그물이다. {@code sk-live-secret} 은 저장 시점에 암호화되므로
   * {@code doesNotContain} 은 그 자체로는 공허하지만(평문이 어차피 안 실린다),
   * {@code agentType}/{@code ai.credential} 문자열은 JSON 구조·키 이름이 흘러나오는지를 잡는다.
   */
  @Test
  void 어떤_범용_경로에도_문서_구조가_섞이지_않는다() {
    String all =
        settingsService.getAll().toString()
            + settingsService.getResolvedByPrefix("ai")
            + settingsService.getAsMap("ai");
    assertThat(all).doesNotContain("sk-live-secret").doesNotContain(KEY).doesNotContain("agentType");
  }

  /** #707 — 분류 전용 묶음도 범용 경로(읽기·쓰기·삭제)로 새거나 반쪽만 바뀌지 않는다. */
  @Test
  void 분류_전용_두_키는_범용_경로로_읽거나_쓰거나_지울_수_없다() {
    aiCredentialService.saveClassify(
        new AiCredentialService.AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "sk-classify")),
        "claude-haiku-4-5",
        USER);

    for (String key : java.util.List.of(AiCredentialSlot.CLASSIFY.key(), AiCredentialSlot.CLASSIFY_MODEL_KEY)) {
      assertThat(settingsService.getResolvedByPrefix("ai"))
          .extracting(com.smartfirehub.settings.dto.ResolvedSettingResponse::key)
          .as(key)
          .doesNotContain(key);
      assertThat(settingsService.getAsMap("ai")).as(key).doesNotContainKey(key);
      assertThatThrownBy(() -> settingsService.getValue(key)).as(key).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> settingsService.updateSettings(Map.of(key, "x"), USER))
          .as(key)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> settingsService.clearOverride(key)).as(key).isInstanceOf(IllegalArgumentException.class);
    }
    // 범용 쓰기가 거부됐으니 모델은 그대로다.
    assertThat(aiCredentialService.readClassify().model()).isEqualTo("claude-haiku-4-5");
  }
}
