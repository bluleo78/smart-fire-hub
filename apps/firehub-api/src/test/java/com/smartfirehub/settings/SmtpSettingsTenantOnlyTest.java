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
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.SettingsOverridePolicy;
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
 * SMTP 설정은 테넌트 전용이다(#712) — 플랫폼({@code system_settings}) 행을 어떤 경로에서도 읽지
 * 않고, 워크스페이스가 저장한 값이 없으면 미설정(빈 값)이다. 코드 기본값도 없다.
 *
 * <p><b>비공허성</b>: V128 이 {@code smtp.*} 플랫폼 행을 지웠으므로, 아무것도 심지 않고 "미설정이다"를
 * 단언하면 플랫폼 폴백이 되살아나도 통과한다. 그래서 매 테스트 전에 6키 전부에 <b>표식 값</b>을
 * 플랫폼 행으로 직접 심고, 결과에 그 표식이 절대 나오지 않는지 본다. 정리는 심은 행 삭제다(V128
 * 이후 원래 행이 없다). {@code AiSettingsTenantOnlyTest}(#706)와 같은 형식이다.
 */
class SmtpSettingsTenantOnlyTest extends IntegrationTestBase {

  /** 키 → 플랫폼에 심는 표식 값. 비밀번호도 평문 그대로 심는다(읽히면 그대로 드러나도록). */
  private static final Map<String, String> PLANTED = new LinkedHashMap<>();

  static {
    PLANTED.put("smtp.host", "planted-platform-smtp.example.com");
    PLANTED.put("smtp.port", "2526");
    PLANTED.put("smtp.username", "planted-platform-user");
    PLANTED.put("smtp.password", "planted-platform-password");
    PLANTED.put("smtp.starttls", "false");
    PLANTED.put("smtp.from_address", "planted-platform@example.com");
  }

  @Autowired private SettingsService settingsService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @BeforeEach
  void plantPlatformRows() {
    // 전제: 표식 키 집합이 정책의 SMTP 6키와 정확히 같다(키가 늘면 이 테스트도 따라와야 한다).
    assertThat(PLANTED.keySet()).containsExactlyInAnyOrderElementsOf(SettingsOverridePolicy.smtpKeys());
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
  void 워크스페이스_값이_없으면_플랫폼_행이_있어도_미설정이다() {
    testTenant = createActiveTenant(dsl, "smtp-unset");
    TenantContext.set(testTenant);

    // 발송 경로 — 세 소비자(EmailChannel·EmailDeliveryChannel·연결 테스트)가 공유하는 진입점.
    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).isEmpty();
    assertThat(config.getOrDefault("smtp.host", "")).isBlank();
    // 단일 키 경로도 미설정이다.
    for (String key : PLANTED.keySet()) {
      assertThat(settingsService.getValue(key)).as(key).isEmpty();
    }
    // 화면 경로 — 저장한 값이 없으면 빈 목록이다.
    assertThat(settingsService.getResolvedByPrefix("smtp")).isEmpty();
  }

  @Test
  void 테넌트_컨텍스트가_없어도_플랫폼_행이_아니라_미설정이다() {
    // 알림 발송 워커·프로액티브 리포트 같은 배경 경로. 예전에는 여기서 플랫폼 SMTP 로 폴백했다.
    TenantContext.clear();

    assertThat(settingsService.getSmtpConfig()).isEmpty();
    for (String key : PLANTED.keySet()) {
      assertThat(settingsService.getValue(key)).as(key).isEmpty();
    }
  }

  @Test
  void 워크스페이스가_저장한_값만_발송에_쓰이고_플랫폼_값은_섞이지_않는다() {
    testTenant = createActiveTenant(dsl, "smtp-tenant-set");
    TenantContext.set(testTenant);
    // 호스트·자격증명만 저장한다 — 예전 두 평면이면 나머지(포트·STARTTLS·발신자)가 플랫폼 값으로 섞였다.
    settingsService.updateSettings(
        Map.of(
            "smtp.host", "smtp.tenant.example.com",
            "smtp.username", "tenant-user",
            "smtp.password", "tenant-secret-password"),
        null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config)
        .containsOnly(
            Map.entry("smtp.host", "smtp.tenant.example.com"),
            Map.entry("smtp.username", "tenant-user"),
            // 암호화 저장된 값이 복호화되어 나온다 — 암호문이면 SMTP 인증이 조용히 실패한다.
            Map.entry("smtp.password", "tenant-secret-password"));
    // 저장하지 않은 starttls 는 키 자체가 없다 — 빈 문자열이 들어 있으면 소비자가 STARTTLS 를
    // 끈 것으로 읽는다(getOrDefault 의 안전한 기본값 "true" 가 적용되지 않는다).
    assertThat(config).doesNotContainKey("smtp.starttls");
    assertThat(config.values()).doesNotContainAnyElementsOf(PLANTED.values());

    // 화면 경로: 저장한 3키만, 전부 "저장된 값", 비밀번호는 마스킹.
    Map<String, ResolvedSettingResponse> resolved = resolvedSmtp();
    assertThat(resolved.keySet())
        .containsExactlyInAnyOrder("smtp.host", "smtp.username", "smtp.password");
    resolved.forEach(
        (key, r) -> {
          assertThat(r.overridden()).as(key).isTrue();
          assertThat(r.tenantEditable()).as(key).isTrue();
        });
    assertThat(resolved.get("smtp.password").value())
        .startsWith("****")
        .doesNotContain("tenant-secret-password")
        .doesNotContain(":");
    assertThat(resolved.values().stream().map(ResolvedSettingResponse::value))
        .doesNotContainAnyElementsOf(PLANTED.values());
  }

  @Test
  void 설정_해제는_SMTP_6키를_모두_지우고_다른_설정은_건드리지_않는다() {
    testTenant = createActiveTenant(dsl, "smtp-clear");
    TenantContext.set(testTenant);
    Map<String, String> all = new LinkedHashMap<>();
    all.put("smtp.host", "smtp.tenant.example.com");
    all.put("smtp.port", "465");
    all.put("smtp.username", "tenant-user");
    all.put("smtp.password", "tenant-secret-password");
    all.put("smtp.starttls", "true");
    all.put("smtp.from_address", "tenant@example.com");
    settingsService.updateSettings(all, null);
    // 해제가 과하게 지우지 않는지 보려고 AI 동작 키 하나도 저장해 둔다.
    settingsService.updateSettings(Map.of("ai.model", "tenant-model"), null);
    // 전제: 6키가 실제로 저장됐다.
    assertThat(tenantRows()).containsOnlyKeys(all.keySet());

    settingsService.clearSmtpSettings();

    assertThat(tenantRows()).isEmpty();
    assertThat(settingsService.getSmtpConfig()).isEmpty();
    // 해제 뒤에도 플랫폼 행으로 되돌아가지 않는다 — 미설정이다.
    assertThat(settingsService.getResolvedByPrefix("smtp")).isEmpty();
    // 다른 네임스페이스는 그대로다.
    assertThat(settingsService.getValue("ai.model")).contains("tenant-model");
    // 플랫폼 행도 그대로다(해제는 tenant_settings 만 지운다).
    PLANTED.forEach((key, value) -> assertThat(rawSystemSettingValue(dsl, key)).isEqualTo(value));

    // 멱등 — 이미 미설정이어도 예외가 없다.
    settingsService.clearSmtpSettings();
    assertThat(tenantRows()).isEmpty();
  }

  @Test
  void 설정_해제는_다른_워크스페이스의_SMTP_를_지우지_않는다() {
    testTenant = createActiveTenant(dsl, "smtp-clear-a");
    long other = createActiveTenant(dsl, "smtp-clear-b");
    try {
      runInTenantTransaction(
          transactionTemplate,
          other,
          () -> tenantSettingsRepository.upsert("smtp.host", "other.example.com", null));

      TenantContext.set(testTenant);
      settingsService.updateSettings(Map.of("smtp.host", "mine.example.com"), null);
      settingsService.clearSmtpSettings();
      assertThat(tenantRows()).isEmpty();

      TenantContext.set(other);
      assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.host", "other.example.com");
    } finally {
      deleteTenants(dsl, other);
    }
  }

  @Test
  void 플랫폼_목록은_SMTP_키를_내보내지_않는다() {
    // 전제는 @BeforeEach 가 보장한다 — 6키 플랫폼 행이 실재하는데도 목록에 없어야 한다.
    assertThat(settingsService.getAll()).noneMatch(s -> s.key().startsWith("smtp."));
  }

  @Test
  void 플랫폼_쓰기는_SMTP_키를_거부하고_행을_바꾸지_않는다() {
    for (String key : PLANTED.keySet()) {
      assertThatThrownBy(
              () -> settingsService.updatePlatformSettings(Map.of(key, "overwrite-attempt"), null))
          .as(key)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("워크스페이스 설정은 플랫폼 설정으로 저장할 수 없습니다")
          .hasMessageContaining(key);
      assertThat(rawSystemSettingValue(dsl, key)).as(key).isEqualTo(PLANTED.get(key));
    }
    // 임베딩 키와 섞어 보내도 통째로 거부된다(부분 저장 없음).
    String modelBefore = rawSystemSettingValue(dsl, "embedding.model");
    assertThatThrownBy(
            () ->
                settingsService.updatePlatformSettings(
                    Map.of("embedding.model", "should-not-save", "smtp.host", "x"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rawSystemSettingValue(dsl, "embedding.model")).isEqualTo(modelBefore);
  }

  /** 현재 테스트 테넌트의 {@code smtp.*} 원시 행(RLS GUC 를 주입한 트랜잭션에서 읽는다). */
  private Map<String, String> tenantRows() {
    return runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.findByPrefix("smtp"));
  }

  private Map<String, ResolvedSettingResponse> resolvedSmtp() {
    return settingsService.getResolvedByPrefix("smtp").stream()
        .collect(Collectors.toMap(ResolvedSettingResponse::key, r -> r));
  }
}
