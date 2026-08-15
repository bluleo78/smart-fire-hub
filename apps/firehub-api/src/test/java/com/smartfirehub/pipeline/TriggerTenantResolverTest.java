package com.smartfirehub.pipeline;

import static com.smartfirehub.jooq.tables.Pipeline.PIPELINE;
import static com.smartfirehub.jooq.tables.PipelineTrigger.PIPELINE_TRIGGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.repository.TriggerTenantResolver;
import com.smartfirehub.pipeline.repository.TriggerTenantResolver.TriggerRef;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V94/V95 — 비인증 트리거 경로의 테넌트 해석을 고정한다.
 *
 * <p>이 경로는 인증 필터를 거치지 않아 요청 시점에 테넌트를 모른다. 해석은 RLS 를 우회하는
 * SECURITY DEFINER 함수가 담당하며, 시스템에서 RLS 우회는 이 함수뿐이다. 그래서 여기서 검증하는
 * 것은 "해석이 되는가" 만이 아니라 <b>노출면이 좁게 유지되는가</b>(id 두 개, PUBLIC 실행 불가)와
 * <b>식별자 유니크가 전역인가</b>(접으면 해석이 모호해진다)다.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 없다 — 서로 다른 테넌트의 커밋된 행이 동시에 존재해야
 * 해석이 테넌트를 구분하는지 볼 수 있기 때문이다. 심은 행은 {@link #cleanup()} 에서 직접 지운다.
 */
class TriggerTenantResolverTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TriggerTenantResolver resolver;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private long tenantA;
  private long tenantB;
  private Long userId;

  /** 정리 순서를 위해 만든 순서대로 (테넌트, 파이프라인 id) 를 모아 둔다. */
  private final List<long[]> pipelines = new ArrayList<>();

  @BeforeEach
  void seed() {
    tx = new TransactionTemplate(transactionManager);
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "trigger-resolver-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "trigger-resolver-b");
    userId = TenantRlsTestSupport.insertUser(dsl, "trigger-resolver-");
  }

  @AfterEach
  void cleanup() {
    // 파이프라인을 지우면 트리거가 FK CASCADE 로 함께 사라진다. 사용자·테넌트는 그 뒤에 지운다.
    for (long[] entry : pipelines) {
      inTenantTx(entry[0], () -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(entry[1])).execute());
    }
    pipelines.clear();
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  /** 해석 함수가 각 트리거의 소유 테넌트를 정확히 돌려주는지 — 두 테넌트가 동시에 존재하는 상태에서. */
  @Test
  @DisplayName("서로 다른 테넌트의 웹훅이 각각 자기 테넌트로 해석된다")
  void resolvesTenantForWebhookOfEitherTenant() {
    String webhookA = UUID.randomUUID().toString();
    String webhookB = UUID.randomUUID().toString();
    long triggerA = insertWebhookTrigger(tenantA, webhookA, true);
    long triggerB = insertWebhookTrigger(tenantB, webhookB, true);

    assertThat(resolver.resolveByWebhookId(webhookA))
        .contains(new TriggerRef(triggerA, tenantA));
    assertThat(resolver.resolveByWebhookId(webhookB))
        .contains(new TriggerRef(triggerB, tenantB));
  }

  /** API 토큰 해시 경로도 같은 보장을 갖는지. 해싱은 TriggerService 가 하고 여기엔 해시가 온다. */
  @Test
  @DisplayName("API 토큰 해시가 소유 테넌트로 해석된다")
  void resolvesTenantForApiTokenHash() {
    String tokenHash = "hash-" + UUID.randomUUID();
    long triggerId = insertApiTrigger(tenantB, tokenHash, true);

    assertThat(resolver.resolveByApiToken(tokenHash)).contains(new TriggerRef(triggerId, tenantB));
  }

  /** 비활성 트리거는 테넌트조차 노출하지 않는다 — is_enabled 필터가 함수 안에 있기 때문이다. */
  @Test
  @DisplayName("비활성 트리거는 해석되지 않는다")
  void disabledTriggerResolvesToEmpty() {
    String webhookId = UUID.randomUUID().toString();
    insertWebhookTrigger(tenantA, webhookId, false);

    String tokenHash = "hash-" + UUID.randomUUID();
    insertApiTrigger(tenantA, tokenHash, false);

    assertThat(resolver.resolveByWebhookId(webhookId)).isEmpty();
    assertThat(resolver.resolveByApiToken(tokenHash)).isEmpty();
  }

  /** 존재하지 않는 식별자는 예외가 아니라 빈 결과여야 한다 — 호출자가 기존 401/404 로 응답한다. */
  @Test
  @DisplayName("없는 식별자는 빈 결과다")
  void unknownIdentifierResolvesToEmpty() {
    assertThat(resolver.resolveByWebhookId(UUID.randomUUID().toString())).isEmpty();
    assertThat(resolver.resolveByApiToken("no-such-hash")).isEmpty();
  }

  /** 외부 식별자 유니크는 '전역'이어야 한다 — 접으면 인증 전 해석이 모호해진다. 이 결정을 고정한다. */
  @Test
  @DisplayName("외부 식별자 유니크 인덱스는 테넌트 스코프가 아니라 전역이다")
  void externalIdentifierUniqueIsGlobalNotTenantScoped() {
    assertThat(indexDef("uq_trigger_webhook_id")).doesNotContain("tenant_id");
    assertThat(indexDef("uq_trigger_api_token_hash")).doesNotContain("tenant_id");
  }

  /** 같은 webhookId 를 서로 다른 테넌트가 가질 수 없다 — 전역 유니크의 실동작 확인. */
  @Test
  @DisplayName("같은 webhookId 는 다른 테넌트라도 두 번 들어갈 수 없다")
  void sameWebhookIdAcrossTenantsIsRejected() {
    String webhookId = UUID.randomUUID().toString();
    insertWebhookTrigger(tenantA, webhookId, true);

    assertThatThrownBy(() -> insertWebhookTrigger(tenantB, webhookId, true))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  /** 함수 실행 권한이 PUBLIC 에 열려 있지 않은지 — 열려 있으면 누구나 RLS 를 우회해 조회할 수 있다. */
  @Test
  @DisplayName("해석 함수는 PUBLIC 에 실행 권한이 없다")
  void resolverFunctionIsNotExecutableByPublic() {
    assertThat(
            dsl.fetchValue(
                "select has_function_privilege('public',"
                    + " 'resolve_trigger_tenant_by_webhook_id(text)', 'EXECUTE')"))
        .isEqualTo(false);
    assertThat(
            dsl.fetchValue(
                "select has_function_privilege('public',"
                    + " 'resolve_trigger_tenant_by_token_hash(text)', 'EXECUTE')"))
        .isEqualTo(false);
  }

  /**
   * 함수가 실제로 SECURITY DEFINER 이고 search_path 가 고정돼 있는지.
   *
   * <p>V96 전에는 {@code pipeline_trigger} 에 정책이 없어, 위 해석 테스트들은 함수가 평범한
   * SECURITY INVOKER 여도 전부 통과한다 — 즉 "RLS 를 우회한다"는 이 태스크의 본질이 공허하게
   * 통과할 수 있다. 그래서 카탈로그를 직접 본다. search_path 고정이 빠지면 definer 함수는 권한
   * 상승 경로가 되므로 함께 고정한다.
   */
  @Test
  @DisplayName("해석 함수는 SECURITY DEFINER 이며 search_path 가 고정돼 있다")
  void resolverFunctionsAreSecurityDefinerWithPinnedSearchPath() {
    for (String name :
        List.of("resolve_trigger_tenant_by_webhook_id", "resolve_trigger_tenant_by_token_hash")) {
      var row =
          dsl.fetchOne("select prosecdef, proconfig::text from pg_proc where proname = ?", name);
      assertThat(row).as("함수 %s 가 존재하지 않는다", name).isNotNull();
      assertThat(row.get("prosecdef", Boolean.class))
          .as("%s 가 SECURITY DEFINER 가 아니면 RLS 를 우회하지 못한다", name)
          .isTrue();
      assertThat(row.get("proconfig", String.class))
          .as("%s 의 search_path 가 고정돼 있어야 한다", name)
          .contains("search_path");
    }
  }

  /**
   * definer 우회가 성립하는 두 전제를 고정한다.
   *
   * <p>이 우회는 (a) 테이블 소유자와 함수 소유자가 같고 런타임 롤이 소유자가 아니라는 것, (b)
   * {@code FORCE ROW LEVEL SECURITY} 가 꺼져 있다는 것에 의존한다. FORCE 는 소유자에게까지 정책을
   * 적용하므로 켜는 순간 definer 함수도 0행을 받고 <b>모든 외부 트리거가 404/401 로 전멸한다</b> —
   * 예외도 로그도 없이. 정책 마이그레이션에서 "일관성" 을 이유로 켜기 쉬운 플래그라 여기서 막는다.
   */
  @Test
  @DisplayName("pipeline_trigger 에 FORCE RLS 가 걸려 있지 않고 소유자가 함수 소유자와 같다")
  void definerBypassPreconditionsHold() {
    var table =
        dsl.fetchOne(
            "select relforcerowsecurity, pg_get_userbyid(relowner) as owner"
                + " from pg_class where relname = 'pipeline_trigger'");
    assertThat(table).isNotNull();
    assertThat(table.get("relforcerowsecurity", Boolean.class))
        .as("FORCE RLS 가 켜지면 SECURITY DEFINER 해석 함수도 0행을 받아 외부 트리거가 전멸한다")
        .isFalse();

    String tableOwner = table.get("owner", String.class);
    String functionOwner =
        (String)
            dsl.fetchValue(
                "select pg_get_userbyid(proowner)::text from pg_proc"
                    + " where proname = 'resolve_trigger_tenant_by_webhook_id'");
    assertThat(functionOwner)
        .as("함수 소유자가 테이블 소유자와 다르면 definer 실행이 RLS 를 우회하지 못한다")
        .isEqualTo(tableOwner);
  }

  // ── 픽스처 헬퍼 ────────────────────────────────────────────────────────

  private long insertWebhookTrigger(long tenantId, String webhookId, boolean enabled) {
    return insertTrigger(
        tenantId, "WEBHOOK", JSONB.valueOf("{\"webhookId\": \"" + webhookId + "\"}"), enabled);
  }

  private long insertApiTrigger(long tenantId, String tokenHash, boolean enabled) {
    return insertTrigger(
        tenantId, "API", JSONB.valueOf("{\"tokenHash\": \"" + tokenHash + "\"}"), enabled);
  }

  /** 테넌트별 파이프라인 + 트리거를 만들고 트리거 id 를 반환한다. tenant_id 는 GUC DEFAULT 가 채운다. */
  private long insertTrigger(long tenantId, String type, JSONB config, boolean enabled) {
    return inTenantTx(
        tenantId,
        () -> {
          long pipelineId =
              dsl.insertInto(PIPELINE)
                  .set(PIPELINE.NAME, "trigger-resolver-" + UUID.randomUUID())
                  .set(PIPELINE.IS_ACTIVE, true)
                  .set(PIPELINE.CREATED_BY, userId)
                  .set(PIPELINE.CREATED_AT, LocalDateTime.now())
                  .returning(PIPELINE.ID)
                  .fetchOne()
                  .getId();
          pipelines.add(new long[] {tenantId, pipelineId});

          return dsl.insertInto(PIPELINE_TRIGGER)
              .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
              .set(PIPELINE_TRIGGER.TRIGGER_TYPE, type)
              .set(PIPELINE_TRIGGER.NAME, "trigger-" + UUID.randomUUID())
              .set(PIPELINE_TRIGGER.IS_ENABLED, enabled)
              .set(PIPELINE_TRIGGER.CONFIG, config)
              .set(PIPELINE_TRIGGER.CREATED_BY, userId)
              .set(PIPELINE_TRIGGER.CREATED_AT, LocalDateTime.now())
              .returning(PIPELINE_TRIGGER.ID)
              .fetchOne()
              .getId();
        });
  }

  private void inTenantTx(long tenantId, Runnable action) {
    TenantRlsTestSupport.runInTenantTransaction(tx, tenantId, action);
  }

  private <T> T inTenantTx(long tenantId, Supplier<T> action) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, tenantId, action);
  }

  /** 인덱스 정의를 읽는다. 인덱스가 없으면 단언이 아니라 명확한 실패 메시지를 내도록 한다. */
  private String indexDef(String indexName) {
    var row =
        dsl.fetchOne(
            "select indexdef from pg_indexes where schemaname='public' and indexname=?", indexName);
    assertThat(row).as("인덱스 %s 가 존재하지 않는다", indexName).isNotNull();
    return row.get("indexdef", String.class);
  }
}
