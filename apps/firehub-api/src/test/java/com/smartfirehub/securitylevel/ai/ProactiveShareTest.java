package com.smartfirehub.securitylevel.ai;

import static com.smartfirehub.jooq.Tables.AUDIT_LOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.service.ProactiveContextCollector;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 스펙 §4.2·§4.3 Proactive 행(Review Focus 4): 리포트 컨텍스트·메트릭은 채팅 공급자로 가고 발송되므로 소유자 VIEW 에 더해 AI(공유 호스팅
 * 규칙)·SHARE 를 통과한 데이터셋만 쓴다. 기밀(share_policy DENY) 데이터셋은 소유자가 볼 수 있고 자체 호스팅으로 선언돼 있어도 리포트에 실리지 않는다.
 */
class ProactiveShareTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ObjectMapper om;
  @Autowired private ProactiveContextCollector collector;
  @Autowired private DashboardService dashboardService;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private ProactiveJobService jobService;
  @Autowired private TenantSettingsRepository tenantSettings;

  private SecurityFixture fx;
  private long owner;
  private long roleId;
  private long pubId;
  private long secretId;
  private String m;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "pxs" + System.nanoTime();
    owner = fx.createUser("pxs");
    fx.removeUserRole(owner);
    roleId = fx.createRole("pxs_r_" + m, fx.levelId("기밀"), "dataset:read", "proactive:write");
    fx.assignRole(owner, roleId);
    pubId = fx.createDatasetRow(m + "_pub", fx.levelId("공개"), owner);
    secretId = fx.createDatasetRow(m + "_secret", fx.levelId("기밀"), owner);
    fx.grantUser(secretId, owner); // 기밀은 허용 목록 필수 — 소유자는 볼 수 있다(VIEW 통과)
    // 최근 임포트 성공·실패 + 생성 이벤트 — 홈 대시보드(최근 임포트·주의 항목·활동 피드)에 두 데이터셋 이름이 실리게 한다.
    for (long id : List.of(pubId, secretId)) {
      audit("IMPORT", id, "SUCCESS");
      audit("IMPORT", id, "FAILURE");
      audit("CREATE", id, "SUCCESS");
    }
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 채팅·임베딩을 자체 호스팅으로 선언(forShare 규칙) — 기밀의 ai_policy(SELF_HOSTED_ONLY)는 통과시키고 SHARE 만 남겨 본다.
    tenantSettings.upsert(
        AiCredentialSlot.CHAT.key(),
        "{\"v\":1,\"agentType\":\"opencode\",\"payload\":{\"providerId\":\"corp\","
            + "\"baseURL\":\"http://10.0.0.5/v1\",\"hosting\":\"SELF_HOSTED\"},\"secret\":{}}",
        null);
    tenantSettings.upsert(
        EmbeddingConfigService.KEY,
        "{\"v\":1,\"provider\":\"OLLAMA\",\"model\":\"bge-m3\",\"baseUrl\":\"http://ollama:11434\","
            + "\"dimension\":1024,\"hosting\":\"SELF_HOSTED\",\"secret\":{\"apiKey\":\"\"}}",
        null);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettings.delete(AiCredentialSlot.CHAT.key());
    tenantSettings.delete(EmbeddingConfigService.KEY);
    inTenantFixture(() -> dsl.execute("delete from proactive_job where user_id = ?", owner));
    fx.deleteDatasetRow(pubId); // dataset.created_by FK 때문에 사용자보다 먼저
    fx.deleteDatasetRow(secretId);
    fx.deleteUser(owner); // owner 명의 감사 로그 포함
    fx.deleteRole(roleId);
  }

  /** owner 명의 감사 로그 — fx.deleteUser 가 user_id 로 정리한다. 지금 시각이라 홈 상위 목록(LIMIT)에 든다. */
  private void audit(String action, long datasetId, String result) {
    inTenantFixture(
        () ->
            dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.USER_ID, owner)
                .set(AUDIT_LOG.USERNAME, "pxs")
                .set(AUDIT_LOG.ACTION_TYPE, action)
                .set(AUDIT_LOG.RESOURCE, "dataset")
                .set(AUDIT_LOG.RESOURCE_ID, String.valueOf(datasetId))
                .set(AUDIT_LOG.RESULT, result)
                .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now())
                .execute());
  }

  @Test
  void proactiveContext_excludesShareDeniedDatasetNames() throws Exception {
    // 대조군: AI 범위 밖(웹 홈과 같은 소유자 VIEW 만)에서는 기밀 데이터셋 이름이 대시보드에 나온다 — 부재 단언이 데이터 준비 부족
    // (LIMIT 밀림 등)으로 공허해지지 않음을 보인다.
    String plain =
        om.writeValueAsString(dashboardService.getStats(clearanceResolver.resolve(owner)));
    assertThat(plain).contains("sf_" + m + "_secret");

    String context = collector.collectContext(Map.of(), null, owner);
    assertThat(context).contains("sf_" + m + "_pub");
    assertThat(context).doesNotContain(m + "_secret");
  }

  /** 메트릭 값도 리포트 컨텍스트로 가고 발송된다 — 기밀 테이블 메트릭은 소유자가 볼 수 있고 자체 호스팅이어도 저장 시점에 SHARE 로 막힌다. */
  @Test
  void anomalyMetric_onShareDeniedDataset_isPolicyBlocked() {
    assertThatThrownBy(() -> jobService.createJob(anomalyJob(m + "_secret"), owner))
        .isInstanceOf(PolicyBlockedException.class)
        .satisfies(
            e -> {
              PolicyBlockedException p = (PolicyBlockedException) e;
              assertThat(p.details()).containsEntry("action", "SHARE");
              assertThat(p.details()).containsEntry("levelName", "기밀");
            });
    // 공유 허용 데이터셋 메트릭은 그대로 저장된다.
    assertThat(jobService.createJob(anomalyJob(m + "_pub"), owner).id()).isPositive();
  }

  private static CreateProactiveJobRequest anomalyJob(String table) {
    return new CreateProactiveJobRequest(
        "pxs-job",
        "보고",
        null,
        null,
        null,
        false,
        "ANOMALY",
        Map.of(
            "anomaly",
            Map.of(
                "metrics",
                List.of(
                    Map.of(
                        "id",
                        "m1",
                        "name",
                        "건수",
                        "source",
                        "dataset",
                        "query",
                        "SELECT count(*) FROM " + table)))));
  }
}
