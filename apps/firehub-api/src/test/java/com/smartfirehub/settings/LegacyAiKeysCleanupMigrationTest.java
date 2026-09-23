package com.smartfirehub.settings;

import static com.smartfirehub.support.SettingsTestSupport.rawTenantSettingValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V129(#699) — 워크스페이스 평면에 남은 옛 AI 3키({@code ai.api_key}/{@code ai.cli_oauth_token}/
 * {@code ai.agent_type})를 지우는 마이그레이션을 검증한다.
 *
 * <p>Flyway 는 테스트 DB 부팅 때 V129 를 이미 적용했으므로, 마이그레이션 이전 상태(옛 3키 + V122 가 만든
 * {@code ai.credential})를 테넌트 하나에 직접 심고 V129 파일 본문을 그대로 재생한다
 * ({@link AiCredentialMigrationTest} 와 같은 방식). 재생은 그 테넌트의 RLS 컨텍스트 안에서 돌아서 다른
 * 테넌트 행에는 닿지 않는다.
 */
class LegacyAiKeysCleanupMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private EncryptionService encryptionService;
  @Autowired private AiCredentialService aiCredentialService;

  private static final String MIGRATION_SQL = loadMigrationSql();

  // 이 테스트 전용 테넌트 — 끝나면 행과 함께 지운다.
  private long tenantId;

  @BeforeEach
  void createTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "legacy-ai-keys");
  }

  @AfterEach
  void deleteTenant() {
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  private static String loadMigrationSql() {
    try (InputStream in =
        LegacyAiKeysCleanupMigrationTest.class.getResourceAsStream(
            "/db/migration/V129__drop_legacy_tenant_ai_keys.sql")) {
      if (in == null) throw new IllegalStateException("V129 마이그레이션 파일을 찾을 수 없다");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void insertRow(String key, String value) {
    inTenantFixture(
        tenantId,
        () ->
            dsl.execute(
                "insert into tenant_settings (tenant_id, key, value, updated_by, updated_at) "
                    + "values (?, ?, ?, null, now())",
                tenantId,
                key,
                value));
  }

  private Optional<String> raw(String key) {
    return rawTenantSettingValue(dsl, fixtureTransactionTemplate, tenantId, key);
  }

  @Test
  void 옛_3키만_지우고_JSON_자격증명과_다른_AI_설정은_남긴다() {
    // V122 이전 옛 평면 3키 — 암호문 사본이 남아 있는 상태를 재현한다.
    insertRow("ai.agent_type", "sdk");
    insertRow("ai.api_key", encryptionService.encrypt("sk-legacy-copy"));
    insertRow("ai.cli_oauth_token", encryptionService.encrypt("oauth-legacy-copy"));
    // V122 가 만든 실제 자격증명 문서와 이웃 ai.* 설정 — 지워지면 안 된다.
    String credentialJson =
        "{\"v\":1,\"agentType\":\"sdk\",\"payload\":{},\"secret\":{\"apiKey\":\""
            + encryptionService.encrypt("sk-live-key")
            + "\"}}";
    insertRow("ai.credential", credentialJson);
    insertRow("ai.model", "claude-sonnet-5");

    inTenantFixture(tenantId, () -> dsl.execute(MIGRATION_SQL));

    assertThat(raw("ai.agent_type")).isEmpty();
    assertThat(raw("ai.api_key")).isEmpty();
    assertThat(raw("ai.cli_oauth_token")).isEmpty();
    assertThat(raw("ai.credential")).contains(credentialJson);
    assertThat(raw("ai.model")).contains("claude-sonnet-5");

    // 실제 소비 경로도 그대로 동작해야 한다 — 옛 키에 기대는 해석이 남아 있었다면 여기서 드러난다.
    AiCredential resolved = TenantContext.runScopedGet(tenantId, aiCredentialService::resolve);
    assertThat(resolved).isInstanceOf(AiCredential.Sdk.class);
    assertThat(((AiCredential.Sdk) resolved).apiKey()).isEqualTo("sk-live-key");
  }
}
