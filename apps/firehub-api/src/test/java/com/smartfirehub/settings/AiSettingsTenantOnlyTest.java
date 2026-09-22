package com.smartfirehub.settings;

import static com.smartfirehub.support.SettingsTestSupport.deleteSystemSetting;
import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.upsertSystemSetting;
import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 설정은 테넌트 전용이다(#706 후속) — 플랫폼({@code system_settings}) 행을 어떤 경로에서도
 * 읽지 않고, 테넌트 값이 없으면 코드 기본값({@link AiBehaviorDefaults})으로 해석된다.
 *
 * <p><b>비공허성</b>: V127 이 {@code ai.*} 플랫폼 행을 지웠으므로, 아무것도 심지 않고 "기본값이
 * 나온다"를 단언하면 플랫폼 폴백이 되살아나도 통과한다. 그래서 매 테스트 전에 6키 전부에
 * 기본값과 <b>다른</b> 표식 값을 플랫폼 행으로 직접 심고, 결과에 그 표식이 절대 나오지 않는지 본다.
 * 정리는 심은 행 삭제다(V127 이후 원래 행이 없다).
 */
class AiSettingsTenantOnlyTest extends IntegrationTestBase {

  /** 키 → 플랫폼에 심는 표식 값. 전부 기본값과 다르고 값 검증도 통과하는 형태다. */
  private static final Map<String, String> PLANTED = new LinkedHashMap<>();

  static {
    PLANTED.put("ai.model", "planted-platform-model");
    PLANTED.put("ai.max_turns", "37");
    PLANTED.put("ai.system_prompt", "planted platform prompt");
    PLANTED.put("ai.temperature", "0.13");
    PLANTED.put("ai.max_tokens", "1234");
    PLANTED.put("ai.session_max_tokens", "77777");
  }

  @Autowired private SettingsService settingsService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @BeforeEach
  void plantPlatformRows() {
    // 전제: 표식 값이 기본값과 달라야 "무시됐다"를 관측할 수 있다.
    PLANTED.forEach(
        (key, value) -> assertThat(value).as(key).isNotEqualTo(AiBehaviorDefaults.defaultOf(key)));
    PLANTED.forEach((key, value) -> upsertSystemSetting(dsl, key, value));
    // 전제: 실제로 심어졌다(심기가 조용히 실패하면 아래 단언이 전부 공허해진다).
    PLANTED.forEach((key, value) -> assertThat(rawSystemSettingValue(dsl, key)).isEqualTo(value));
  }

  @AfterEach
  void cleanup() {
    PLANTED.keySet().forEach(key -> deleteSystemSetting(dsl, key));
    if (testTenant != null) {
      deleteTenants(dsl, testTenant);
      testTenant = null;
    }
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void 테넌트_값이_없으면_플랫폼_행이_있어도_코드_기본값이다() {
    testTenant = createActiveTenant(dsl, "ai-to-default");
    TenantContext.set(testTenant);

    for (String key : AiBehaviorDefaults.keys()) {
      assertThat(settingsService.getValue(key)).as(key).contains(AiBehaviorDefaults.defaultOf(key));
    }
    // 채팅 프록시가 읽는 경로.
    assertThat(settingsService.getAsMap("ai")).containsAllEntriesOf(AiBehaviorDefaults.all());
    // 화면이 읽는 경로 — 6키 전부, 기본값, "저장된 값 아님", 편집 가능.
    Map<String, ResolvedSettingResponse> resolved = resolvedAi();
    assertThat(resolved.keySet()).containsExactlyInAnyOrderElementsOf(AiBehaviorDefaults.keys());
    resolved.forEach(
        (key, r) -> {
          assertThat(r.value()).as(key).isEqualTo(AiBehaviorDefaults.defaultOf(key));
          assertThat(r.overridden()).as(key).isFalse();
          assertThat(r.tenantEditable()).as(key).isTrue();
          assertThat(r.description()).as(key).isNull();
        });
  }

  @Test
  void 테넌트_값이_있으면_그_값이고_나머지는_코드_기본값이다() {
    testTenant = createActiveTenant(dsl, "ai-tenant-set");
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> {
          tenantSettingsRepository.upsert("ai.model", "tenant-model", null);
          tenantSettingsRepository.upsert("ai.max_turns", "5", null);
        });
    TenantContext.set(testTenant);

    assertThat(settingsService.getValue("ai.model")).contains("tenant-model");
    assertThat(settingsService.getValue("ai.max_turns")).contains("5");
    assertThat(settingsService.getValue("ai.temperature"))
        .contains(AiBehaviorDefaults.defaultOf("ai.temperature"));

    Map<String, String> asMap = settingsService.getAsMap("ai");
    assertThat(asMap).containsEntry("ai.model", "tenant-model").containsEntry("ai.max_turns", "5");
    assertThat(asMap)
        .containsEntry("ai.system_prompt", AiBehaviorDefaults.SYSTEM_PROMPT)
        .containsEntry("ai.max_tokens", String.valueOf(AiBehaviorDefaults.MAX_TOKENS));
    assertThat(asMap.values()).doesNotContainAnyElementsOf(PLANTED.values());

    Map<String, ResolvedSettingResponse> resolved = resolvedAi();
    assertThat(resolved.get("ai.model").value()).isEqualTo("tenant-model");
    assertThat(resolved.get("ai.model").overridden()).isTrue();
    assertThat(resolved.get("ai.temperature").overridden()).isFalse();
    assertThat(resolved.values().stream().map(ResolvedSettingResponse::value))
        .doesNotContainAnyElementsOf(PLANTED.values());
  }

  @Test
  void 테넌트_컨텍스트가_없어도_플랫폼_행이_아니라_코드_기본값이다() {
    TenantContext.clear();
    for (String key : AiBehaviorDefaults.keys()) {
      assertThat(settingsService.getValue(key)).as(key).contains(AiBehaviorDefaults.defaultOf(key));
    }
    assertThat(settingsService.getAsMap("ai")).containsAllEntriesOf(AiBehaviorDefaults.all());
  }

  @Test
  void 테넌트_값을_지우면_코드_기본값으로_돌아간다() {
    testTenant = createActiveTenant(dsl, "ai-clear");
    TenantContext.set(testTenant);
    settingsService.updateSettings(Map.of("ai.model", "tenant-model"), null);
    assertThat(settingsService.getValue("ai.model")).contains("tenant-model");

    settingsService.clearOverride("ai.model");

    assertThat(settingsService.getValue("ai.model")).contains(AiBehaviorDefaults.MODEL);
  }

  @Test
  void 플랫폼_목록은_AI_키를_내보내지_않는다() {
    assertThat(settingsService.getAll()).noneMatch(s -> s.key().startsWith("ai."));
  }

  @Test
  void 플랫폼_쓰기는_AI_키를_거부하고_행을_바꾸지_않는다() {
    for (String key : AiBehaviorDefaults.keys()) {
      assertThatThrownBy(
              () ->
                  settingsService.updatePlatformSettings(
                      Map.of(key, AiBehaviorDefaults.defaultOf(key)), null))
          .as(key)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("AI 설정은 플랫폼 설정이 아닙니다");
      assertThat(rawSystemSettingValue(dsl, key)).as(key).isEqualTo(PLANTED.get(key));
    }
  }

  private Map<String, ResolvedSettingResponse> resolvedAi() {
    return settingsService.getResolvedByPrefix("ai").stream()
        .collect(Collectors.toMap(ResolvedSettingResponse::key, r -> r));
  }
}
