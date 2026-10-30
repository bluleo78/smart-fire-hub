package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.TriggerFireResult;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.pipeline.exception.PipelineNameConflictException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 파이프라인·트리거 변경 감사와 실행 감사의 시작 주체(WD-462).
 *
 * <p>거부 경로는 409(이름 중복)·400(금지 SQL·잘못된 cron·자기 연쇄)·404(다른 파이프라인 경로)·403(볼 수 없는 감시 데이터셋)을 본다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다(RoleAuditServiceTest 와 같은 이유) — 감사 행은 서비스 트랜잭션에서 쓰이므로,
 * 커밋된 행을 별도 트랜잭션({@code inTenantFixture})으로 읽어야 "실제로 남았다/거부되면 남지 않았다"의 증거가 된다. 연쇄 실행 검증도 비동기 러너가
 * 커밋된 픽스처를 봐야 한다.
 */
class PipelineTriggerAuditTest extends IntegrationTestBase {

  /** 스텝 코드·시크릿이 감사에 새는지 확인할 표식 문자열. */
  private static final String SQL_MARKER = "wd462_sql_marker";

  private static final String SQL_MARKER_2 = "wd462_sql_marker_two";
  private static final String WEBHOOK_SECRET_MARKER = "wd462-webhook-secret-marker";

  @Autowired private PipelineService pipelineService;
  @Autowired private TriggerService triggerService;
  @Autowired private TriggerSchedulerService schedulerService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  private Long userId;
  private String username;
  private String unique;

  /** 정리 대상 — 이 테스트가 만든 파이프라인(트리거·실행은 FK CASCADE 로 함께 지워진다). */
  private final List<Long> pipelines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    unique = String.valueOf(System.nanoTime());
    username = "wd462_" + unique;
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-462 Audit User")
            .set(USER.EMAIL, username + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    // 트리거 발화는 실행 주체(생성자)의 pipeline:execute 를 확인한다(WD-167) — 실행 권한을 준다.
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
  }

  @AfterEach
  void cleanup() throws InterruptedException {
    // 비동기 실행(연쇄 포함)이 끝난 뒤 지워야 러너가 지워진 행을 건드리지 않는다.
    awaitNoActiveExecution(30_000);
    List<Long> triggerIds =
        inTenantFixture(
            () ->
                dsl.select(field(name("id"), Long.class))
                    .from(table(name("pipeline_trigger")))
                    .where(field(name("pipeline_id"), Long.class).in(pipelines))
                    .fetch(r -> r.value1()));
    triggerIds.forEach(schedulerService::unregisterSchedule);
    inTenantFixture(() -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.in(pipelines)).execute());
    // 이 테스트의 감사 행 — 행위자 행(user_id FK) 과 함께, 행위자 없는(system) 행이 생겼다면 그것도 지운다.
    inTenantFixture(
        () -> {
          dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(userId)).execute();
          dsl.execute(
              "delete from audit_log where user_id is null and description like ?",
              "%" + unique + "%");
        });
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────

  /** 표식이 든 SQL 스텝 하나 — 출력 미지정(자동 생성)이라 데이터셋 픽스처가 필요 없다. */
  private static PipelineStepRequest sqlStep(String marker) {
    return new PipelineStepRequest("step1", null, "SQL", "SELECT 1 AS " + marker, null, null, null);
  }

  private PipelineDetailResponse createPipeline(String name, List<PipelineStepRequest> steps) {
    PipelineDetailResponse created =
        pipelineService.createPipeline(new CreatePipelineRequest(name, null, steps), userId);
    pipelines.add(created.id());
    return created;
  }

  /** 감사 행(id 순). resource·resourceId·action 으로 거른다. */
  private List<Record> audits(String action, String resource, Long resourceId) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                "select user_id, username, tenant_id, description, result, metadata::text as metadata"
                    + " from audit_log where action_type = ? and resource = ? and resource_id = ?"
                    + " order by id",
                action,
                resource,
                String.valueOf(resourceId)));
  }

  /** 설명에 특정 문자열이 든 이 사용자의 감사 행 수 — resource id 를 모르는 거부 경로(생성 실패) 확인용. */
  private int auditCountByDescription(String action, String descriptionPart) {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                dsl.selectFrom(AUDIT_LOG)
                    .where(AUDIT_LOG.ACTION_TYPE.eq(action))
                    .and(AUDIT_LOG.DESCRIPTION.contains(descriptionPart))));
  }

  private JsonNode metadataOf(Record row) throws Exception {
    return objectMapper.readTree(row.get("metadata", String.class));
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  /** 행위자·테넌트·결과 공통 단언. */
  private void assertActor(Record row) {
    assertThat(row.get("user_id", Long.class)).isEqualTo(userId);
    assertThat(row.get("username", String.class)).isEqualTo(username);
    assertThat(row.get("tenant_id", Long.class)).isEqualTo(DEFAULT_TEST_TENANT_ID);
    assertThat(row.get("result", String.class)).isEqualTo("SUCCESS");
  }

  /** 설명·metadata 어디에도 표식이 없어야 한다. */
  private static void assertNoLeak(Record row, String... markers) {
    String description = row.get("description", String.class);
    String metadata = row.get("metadata", String.class);
    for (String marker : markers) {
      assertThat(description).doesNotContain(marker);
      assertThat(metadata).doesNotContain(marker);
    }
  }

  // ── 파이프라인 ─────────────────────────────────────────────────────

  @Test
  void createPipeline_writesCreateAudit_withoutStepCode() throws Exception {
    String name = "audit-create-" + unique;
    PipelineDetailResponse created = createPipeline(name, List.of(sqlStep(SQL_MARKER)));

    List<Record> rows = audits("CREATE", "pipeline", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("파이프라인 생성: " + name);
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo(name);
    assertThat(meta.get("stepCount").asInt()).isEqualTo(1);
    assertNoLeak(rows.get(0), SQL_MARKER, "SELECT");
  }

  @Test
  void updatePipeline_recordsOnlyChangedFieldNames_withoutStepCode() throws Exception {
    String name = "audit-update-" + unique;
    PipelineDetailResponse created = createPipeline(name, List.of(sqlStep(SQL_MARKER)));

    // 이름과 스텝 SQL 을 바꾼다 — 설명·활성 여부는 그대로 보낸다(웹처럼 전체를 보내도 바뀐 것만 세야 한다).
    String renamed = name + "-renamed";
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(renamed, null, true, List.of(sqlStep(SQL_MARKER_2))),
        userId);

    List<Record> rows = audits("UPDATE", "pipeline", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class))
        .isEqualTo("파이프라인 수정: " + renamed + " (바뀐 항목: 이름·스텝)");
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo(renamed);
    assertThat(texts(meta.get("changedFields"))).containsExactly("name", "steps");
    assertNoLeak(rows.get(0), SQL_MARKER, SQL_MARKER_2, "SELECT");

    // 활성 여부만 바꾸면 그것만 남는다.
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(renamed, null, false, List.of(sqlStep(SQL_MARKER_2))),
        userId);
    rows = audits("UPDATE", "pipeline", created.id());
    assertThat(rows).hasSize(2);
    assertThat(texts(metadataOf(rows.get(1)).get("changedFields"))).containsExactly("isActive");
  }

  @Test
  void updatePipeline_withoutChanges_writesNothing() {
    String name = "audit-noop-" + unique;
    PipelineDetailResponse created = createPipeline(name, List.of(sqlStep(SQL_MARKER)));

    // 저장된 그대로 다시 보낸다(웹의 「변경 없이 저장」).
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(name, null, true, List.of(sqlStep(SQL_MARKER))),
        userId);

    assertThat(audits("UPDATE", "pipeline", created.id())).isEmpty();
  }

  @Test
  void deletePipeline_writesDeleteAudit() throws Exception {
    String name = "audit-delete-" + unique;
    PipelineDetailResponse created = createPipeline(name, List.of(sqlStep(SQL_MARKER)));

    pipelineService.deletePipeline(created.id(), userId);

    List<Record> rows = audits("DELETE", "pipeline", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("파이프라인 삭제: " + name);
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo(name);
    assertThat(meta.get("stepCount").asInt()).isEqualTo(1);
    assertNoLeak(rows.get(0), SQL_MARKER);
  }

  @Test
  void rejectedPipelineChanges_writeNothing() {
    String name = "audit-reject-" + unique;
    PipelineDetailResponse created = createPipeline(name, List.of());
    String other = "audit-reject-other-" + unique;
    createPipeline(other, List.of());

    // 이름 중복 생성(409) — 생성 감사가 하나 더 생기지 않는다.
    assertThatThrownBy(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest(name, null, List.of()), userId))
        .isInstanceOf(PipelineNameConflictException.class);
    assertThat(auditCountByDescription("CREATE", "파이프라인 생성: " + name)).isEqualTo(1);

    // 금지 SQL 스텝 생성(400) — 감사가 남지 않는다.
    String unsafe = "audit-unsafe-" + unique;
    assertThatThrownBy(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest(
                        unsafe,
                        null,
                        List.of(
                            new PipelineStepRequest(
                                "s",
                                null,
                                "SQL",
                                "SELECT * FROM public.\"user\"",
                                null,
                                null,
                                null))),
                    userId))
        .isInstanceOf(UnsafeSqlException.class);
    assertThat(auditCountByDescription("CREATE", unsafe)).isZero();

    // 다른 파이프라인 이름으로 바꾸는 수정(409) — 수정 감사가 남지 않는다.
    assertThatThrownBy(
            () ->
                pipelineService.updatePipeline(
                    created.id(), new UpdatePipelineRequest(other, null, true, null), userId))
        .isInstanceOf(PipelineNameConflictException.class);
    assertThat(audits("UPDATE", "pipeline", created.id())).isEmpty();
  }

  // ── 트리거 ─────────────────────────────────────────────────────────

  private TriggerResponse createScheduleTrigger(Long pipelineId, String name) {
    return triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest(
            name,
            TriggerType.SCHEDULE,
            null,
            // 1월 1일 01:00 — 테스트 중 실제로 발화하지 않는다.
            Map.of("cron", "0 1 1 1 *", "timezone", "Asia/Seoul", "concurrencyPolicy", "SKIP")),
        userId);
  }

  @Test
  void createTrigger_writesCreateAudit_withoutSecrets() throws Exception {
    String pipelineName = "audit-trg-" + unique;
    Long pipelineId = createPipeline(pipelineName, List.of()).id();

    TriggerResponse webhook =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "hook-" + unique,
                TriggerType.WEBHOOK,
                null,
                Map.of("secret", WEBHOOK_SECRET_MARKER)),
            userId);
    TriggerResponse api =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("api-" + unique, TriggerType.API, null, Map.of()),
            userId);
    String rawToken = String.valueOf(api.config().get("rawToken"));

    List<Record> rows = audits("CREATE", "pipeline_trigger", webhook.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("트리거 생성: hook-" + unique);
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo("hook-" + unique);
    assertThat(meta.get("triggerType").asText()).isEqualTo("WEBHOOK");
    assertThat(meta.get("pipelineId").asLong()).isEqualTo(pipelineId);
    assertThat(meta.get("pipelineName").asText()).isEqualTo(pipelineName);
    assertThat(meta.has("config")).isFalse();
    assertNoLeak(rows.get(0), WEBHOOK_SECRET_MARKER, "secretEncrypted", "webhookId");

    List<Record> apiRows = audits("CREATE", "pipeline_trigger", api.id());
    assertThat(apiRows).hasSize(1);
    assertNoLeak(apiRows.get(0), rawToken, "tokenHash");
  }

  @Test
  void updateTrigger_recordsChangedConfigKeyNamesOnly() throws Exception {
    Long pipelineId = createPipeline("audit-trg-upd-" + unique, List.of()).id();
    TriggerResponse created = createScheduleTrigger(pipelineId, "sched-" + unique);

    // cron 과 시간대만 바꾼다 — 동시 실행 정책은 같은 값으로 다시 보낸다.
    triggerService.updateTrigger(
        pipelineId,
        created.id(),
        new UpdateTriggerRequest(
            "sched-" + unique,
            null,
            null,
            Map.of("cron", "0 2 1 1 *", "timezone", "UTC", "concurrencyPolicy", "SKIP")),
        userId);

    List<Record> rows = audits("UPDATE", "pipeline_trigger", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class))
        .isEqualTo("트리거 수정: sched-" + unique + " (바뀐 항목: 실행 주기·시간대)");
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(texts(meta.get("changedFields"))).containsExactly("cron", "timezone");
    assertThat(meta.get("triggerType").asText()).isEqualTo("SCHEDULE");
    // 값은 싣지 않는다 — 이름만.
    assertNoLeak(rows.get(0), "0 2 1 1", "UTC");

    // 같은 값 재저장(서버가 cron 을 정규화해 돌려준 값과 같은 의미)은 남기지 않는다.
    triggerService.updateTrigger(
        pipelineId,
        created.id(),
        new UpdateTriggerRequest("sched-" + unique, null, null, Map.of("timezone", "UTC")),
        userId);
    assertThat(audits("UPDATE", "pipeline_trigger", created.id())).hasSize(1);

    // 이름만 바꾸면 그것만.
    triggerService.updateTrigger(
        pipelineId,
        created.id(),
        new UpdateTriggerRequest("sched2-" + unique, null, null, null),
        userId);
    rows = audits("UPDATE", "pipeline_trigger", created.id());
    assertThat(rows).hasSize(2);
    assertThat(texts(metadataOf(rows.get(1)).get("changedFields"))).containsExactly("name");
  }

  @Test
  void updateWebhookTrigger_doesNotLeakSecret() throws Exception {
    Long pipelineId = createPipeline("audit-hook-upd-" + unique, List.of()).id();
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "hook-" + unique, TriggerType.WEBHOOK, null, Map.of("secret", "first-secret")),
            userId);

    // 수정 요청에 평문 시크릿을 실어도 서버는 버리고(WD-220) 감사에도 남지 않는다.
    triggerService.updateTrigger(
        pipelineId,
        created.id(),
        new UpdateTriggerRequest(
            "hook2-" + unique, null, "desc", Map.of("secret", WEBHOOK_SECRET_MARKER)),
        userId);

    List<Record> rows = audits("UPDATE", "pipeline_trigger", created.id());
    assertThat(rows).hasSize(1);
    assertThat(texts(metadataOf(rows.get(0)).get("changedFields")))
        .containsExactly("name", "description");
    assertNoLeak(rows.get(0), WEBHOOK_SECRET_MARKER, "first-secret", "secretEncrypted");
  }

  @Test
  void toggleTrigger_writesStatusChangeAudit() throws Exception {
    Long pipelineId = createPipeline("audit-trg-tgl-" + unique, List.of()).id();
    TriggerResponse created = createScheduleTrigger(pipelineId, "tgl-" + unique);

    triggerService.toggleTrigger(pipelineId, created.id(), false, userId);
    triggerService.toggleTrigger(pipelineId, created.id(), true, userId);

    List<Record> rows = audits("STATUS_CHANGE", "pipeline_trigger", created.id());
    assertThat(rows).hasSize(2);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("트리거 끄기: tgl-" + unique);
    assertThat(metadataOf(rows.get(0)).get("enabled").asBoolean()).isFalse();
    assertThat(rows.get(1).get("description", String.class)).isEqualTo("트리거 켜기: tgl-" + unique);
    assertThat(metadataOf(rows.get(1)).get("enabled").asBoolean()).isTrue();

    // 이미 켜진 트리거를 다시 켜면 바뀐 것이 없어 남지 않는다.
    triggerService.toggleTrigger(pipelineId, created.id(), true, userId);
    assertThat(audits("STATUS_CHANGE", "pipeline_trigger", created.id())).hasSize(2);
  }

  @Test
  void deleteTrigger_writesDeleteAudit() throws Exception {
    String pipelineName = "audit-trg-del-" + unique;
    Long pipelineId = createPipeline(pipelineName, List.of()).id();
    TriggerResponse created = createScheduleTrigger(pipelineId, "del-" + unique);

    triggerService.deleteTrigger(pipelineId, created.id(), userId);

    List<Record> rows = audits("DELETE", "pipeline_trigger", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("트리거 삭제: del-" + unique);
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("triggerType").asText()).isEqualTo("SCHEDULE");
    assertThat(meta.get("pipelineName").asText()).isEqualTo(pipelineName);
  }

  @Test
  void rejectedTriggerChanges_writeNothing() {
    Long pipelineId = createPipeline("audit-trg-rej-" + unique, List.of()).id();
    Long otherPipelineId = createPipeline("audit-trg-rej2-" + unique, List.of()).id();
    TriggerResponse created = createScheduleTrigger(pipelineId, "rej-" + unique);

    // 다른 파이프라인 경로로 삭제·켜기/끄기·수정(404)
    assertThatThrownBy(() -> triggerService.deleteTrigger(otherPipelineId, created.id(), userId))
        .isInstanceOf(TriggerNotFoundException.class);
    assertThatThrownBy(
            () -> triggerService.toggleTrigger(otherPipelineId, created.id(), false, userId))
        .isInstanceOf(TriggerNotFoundException.class);
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    otherPipelineId,
                    created.id(),
                    new UpdateTriggerRequest("x-" + unique, null, null, null),
                    userId))
        .isInstanceOf(TriggerNotFoundException.class);
    // 잘못된 cron(400)
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    pipelineId,
                    created.id(),
                    new UpdateTriggerRequest(
                        "rej-" + unique, null, null, Map.of("cron", "not a cron")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class);
    // 자기 자신을 선행으로 하는 연쇄 생성 거부
    assertThatThrownBy(
            () ->
                triggerService.createTrigger(
                    pipelineId,
                    new CreateTriggerRequest(
                        "self-" + unique,
                        TriggerType.PIPELINE_CHAIN,
                        null,
                        Map.of("upstreamPipelineId", pipelineId, "condition", "SUCCESS")),
                    userId))
        .isInstanceOf(RuntimeException.class);

    // 볼 수 없는(여기서는 없는) 데이터셋을 감시 대상으로 넣는 데이터 변경 트리거(403) — ADMIN 이어도 없는 id 는 거부된다.
    assertThatThrownBy(
            () ->
                triggerService.createTrigger(
                    pipelineId,
                    new CreateTriggerRequest(
                        "hidden-" + unique,
                        TriggerType.DATASET_CHANGE,
                        null,
                        Map.of("datasetIds", List.of(999_999_999L))),
                    userId))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));

    assertThat(audits("DELETE", "pipeline_trigger", created.id())).isEmpty();
    assertThat(audits("STATUS_CHANGE", "pipeline_trigger", created.id())).isEmpty();
    assertThat(audits("UPDATE", "pipeline_trigger", created.id())).isEmpty();
    assertThat(auditCountByDescription("CREATE", "self-" + unique)).isZero();
    assertThat(auditCountByDescription("CREATE", "hidden-" + unique)).isZero();
  }

  // ── 실행(EXECUTE) ─────────────────────────────────────────────────

  @Test
  void manualExecute_recordsExecutionIdAndManualKind() throws Exception {
    String name = "audit-exec-" + unique;
    Long pipelineId = createPipeline(name, List.of()).id();

    PipelineExecutionResponse execution = pipelineService.executePipeline(pipelineId, userId);

    List<Record> rows = audits("EXECUTE", "pipeline", pipelineId);
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class))
        .isEqualTo("파이프라인 실행: " + name + " (실행 #" + execution.id() + ")");
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("executionId").asLong()).isEqualTo(execution.id());
    assertThat(meta.get("triggeredBy").asText()).isEqualTo("MANUAL");
    assertThat(meta.get("triggerId").isNull()).isTrue();
  }

  @Test
  void scheduleFire_recordsScheduleKindAndTriggerId() throws Exception {
    Long pipelineId = createPipeline("audit-exec-sched-" + unique, List.of()).id();
    TriggerResponse trigger = createScheduleTrigger(pipelineId, "fire-" + unique);

    // 스케줄러가 부르는 발화 경로를 직접 부른다(cron 시각을 기다리지 않는다).
    TriggerFireResult result = triggerService.fireTrigger(trigger.id(), Map.of());
    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.FIRED);

    List<Record> rows = audits("EXECUTE", "pipeline", pipelineId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("description", String.class))
        .endsWith("(실행 #" + result.executionId() + ")");
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("executionId").asLong()).isEqualTo(result.executionId());
    assertThat(meta.get("triggeredBy").asText()).isEqualTo("SCHEDULE");
    assertThat(meta.get("triggerId").asLong()).isEqualTo(trigger.id());
  }

  @Test
  void chainRun_recordsChainKindDistinctFromManualRun() throws Exception {
    Long upstream = createPipeline("audit-chain-up-" + unique, List.of()).id();
    Long downstream = createPipeline("audit-chain-down-" + unique, List.of()).id();
    TriggerResponse chain =
        triggerService.createTrigger(
            downstream,
            new CreateTriggerRequest(
                "chain-" + unique,
                TriggerType.PIPELINE_CHAIN,
                null,
                Map.of("upstreamPipelineId", upstream, "condition", "SUCCESS")),
            userId);

    // 사람이 선행을 실행하면 선행이 끝난 뒤 후행이 연쇄로 실행된다(스텝 없는 파이프라인은 곧바로 성공).
    pipelineService.executePipeline(upstream, userId);
    long deadline = System.currentTimeMillis() + 30_000;
    while (audits("EXECUTE", "pipeline", downstream).isEmpty()
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(200);
    }

    JsonNode manual = metadataOf(audits("EXECUTE", "pipeline", upstream).get(0));
    assertThat(manual.get("triggeredBy").asText()).isEqualTo("MANUAL");

    List<Record> rows = audits("EXECUTE", "pipeline", downstream);
    assertThat(rows).hasSize(1);
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("triggeredBy").asText()).isEqualTo("PIPELINE_CHAIN");
    assertThat(meta.get("triggerId").asLong()).isEqualTo(chain.id());
    assertThat(rows.get(0).get("description", String.class))
        .endsWith("(실행 #" + meta.get("executionId").asLong() + ")");
  }

  /** 이 테스트 파이프라인의 실행이 모두 끝날 때까지 기다린다(정리 전에 비동기 러너가 멈추게). */
  private void awaitNoActiveExecution(long timeoutMs) throws InterruptedException {
    if (pipelines.isEmpty()) {
      return;
    }
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      int active =
          inTenantFixture(
              () ->
                  dsl.fetchCount(
                      dsl.selectFrom(PIPELINE_EXECUTION)
                          .where(PIPELINE_EXECUTION.PIPELINE_ID.in(pipelines))
                          .and(PIPELINE_EXECUTION.STATUS.in("PENDING", "RUNNING"))));
      if (active == 0) {
        return;
      }
      Thread.sleep(200);
    }
  }
}
