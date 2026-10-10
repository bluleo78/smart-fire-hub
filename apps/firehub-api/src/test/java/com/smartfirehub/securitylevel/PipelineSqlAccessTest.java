package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.PIPELINE_STEP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.service.PipelineExecutionService;
import com.smartfirehub.pipeline.service.PipelineSecurityGate;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository.GrantSubject;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListener;

/**
 * 스펙 §4.2 5행: 파이프라인 SQL 스텝은 저장 시 편집자, 실행 시 실행 주체(수동=실행자, 트리거=트리거 생성자) 기준으로 requireSql 판정한다. 판단 사항
 * 4·5·6.
 *
 * <p>실패 단언(FAILED)마다 같은 SQL 이 자격 있는 실행 주체로는 COMPLETED 가 되는 대조군을 둔다 — 그래야 FAILED 가 SQL 검증·컬럼 불일치 같은
 * 다른 이유가 아니라 보안 판정 때문임이 드러난다.
 */
class PipelineSqlAccessTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private TriggerService triggerService;
  @Autowired private ApplicationEventMulticaster multicaster;
  @Autowired private PipelineSecurityGate pipelineSecurityGate;
  @Autowired private PipelineExecutionRepository executionRepository;
  @Autowired private DatasetSecurityService datasetSecurityService;
  @Autowired private SecurityLevelRepository levelRepository;

  /** 커밋된 데이터셋 등급 변경 이벤트(전파의 "정확히 1회 발행" 단언용 — 테스트가 데이터셋 id 로 거른다). */
  private final List<DatasetSecurityLevelChangedEvent> levelEvents = new CopyOnWriteArrayList<>();

  private ApplicationListener<PayloadApplicationEvent<Object>> levelListener;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> pipelines = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String m;
  private long owner;
  private String secTable;
  private String pubTable;
  private long lowOutId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "pa" + System.nanoTime();
    owner = fx.createUser("pa_owner");
    users.add(owner);
    secTable = m + "_sec";
    table(secTable, "민감");
    // 입력에 행이 있어야 "출력에 쓰지 않았다"는 단언이 공허하지 않다.
    insertRow(secTable, "secret");
    pubTable = m + "_pub";
    table(pubTable, "공개");
    insertRow(pubTable, "p");
    lowOutId = table(m + "_low", "공개");
    levelListener =
        TransactionalApplicationListener.forPayload(
            TransactionPhase.AFTER_COMMIT,
            payload -> {
              if (payload instanceof DatasetSecurityLevelChangedEvent e) {
                levelEvents.add(e);
              }
            });
    multicaster.addApplicationListener(levelListener);
  }

  /** 데이터셋을 만들고 등급을 직접 지정한다(등급 변경 API 의 자격 검사는 이 TC 의 관심사가 아니다). */
  private long table(String t, String level) {
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    t,
                    t,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                owner)
            .id();
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(id))
                .execute());
    datasets.add(id);
    return id;
  }

  private void setLevel(long datasetId, String level) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(datasetId))
                .execute());
  }

  private void insertRow(String t, String v) {
    inTenantFixture(
        () -> dsl.execute("INSERT INTO " + DataSchema.qualify(t) + " (v) VALUES (?)", v));
  }

  private int rowCount(String t) {
    return inTenantFixture(
        () -> dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(t)).get(0, Integer.class));
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격의 역할 하나만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("pa_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "pa_r_" + System.nanoTime(),
            fx.levelId(level),
            "pipeline:read",
            "pipeline:write",
            "pipeline:execute");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private long pipeline(long editor, String sql, Long outputDatasetId) {
    return pipeline(
        editor,
        List.of(
            new PipelineStepRequest(
                "step", null, "SQL", sql, outputDatasetId, null, null, "REPLACE")));
  }

  private long pipeline(long editor, List<PipelineStepRequest> steps) {
    long id =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("PA " + m + " " + System.nanoTime(), "보안 등급 TC", steps),
                editor)
            .id();
    pipelines.add(id);
    return id;
  }

  @AfterEach
  void tearDown() {
    multicaster.removeApplicationListener(levelListener);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long p : pipelines) {
      // 러너가 만든 TEMP 출력(ptmp_<pipelineId>_*)부터 지운다.
      List<Long> temps =
          inTenantFixture(
              () ->
                  dsl.select(DATASET.ID)
                      .from(DATASET)
                      .where(DATASET.TABLE_NAME.like("ptmp\\_" + p + "\\_%"))
                      .fetch(DATASET.ID));
      temps.forEach(
          id -> {
            try {
              datasetService.deleteDataset(id);
            } catch (Exception ignored) {
              // 정리 실패는 다음 정리에 맡긴다.
            }
          });
      try {
        pipelineService.deletePipeline(p);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다.
      }
    }
    datasets.forEach(
        id -> {
          try {
            datasetService.deleteDataset(id);
          } catch (Exception ignored) {
            // 정리 실패는 다음 정리에 맡긴다.
          }
        });
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  @Test
  void save_hiddenTable_rejectedForEditor() {
    long editor = userAt("공개");
    assertThatThrownBy(() -> pipeline(editor, "SELECT v FROM " + qualified(secTable), null))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  @Test
  void save_stepRefPlaceholder_isNotJudgedAtSaveTime() {
    long editor = userAt("공개");
    assertThat(pipeline(editor, "SELECT * FROM {{#1}}", null)).isPositive();
  }

  @Test
  void run_manual_judgesExecutorNotEditor() throws Exception {
    long editor = userAt("민감");
    long p = pipeline(editor, "SELECT v FROM " + qualified(secTable), null);
    assertThat(waitForEnd(executionService.executePipeline(p, userAt("공개")))).isEqualTo("FAILED");
    assertThat(waitForEnd(executionService.executePipeline(p, userAt("민감"))))
        .isEqualTo("COMPLETED");
  }

  /** 보충 스펙 §3 — 파이프라인 실행 거부는 실행 주체를 행위자로 감사된다(실제 테이블명은 감사에만). */
  @Test
  void run_denied_isAuditedWithRunAsActor() throws Exception {
    long editor = userAt("민감");
    long p = pipeline(editor, "SELECT v FROM " + qualified(secTable), null);
    long runner = userAt("공개");
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("FAILED");
    awaitSecurityAudit();
    var row =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                    "SELECT metadata->>'action' a, metadata->>'tableName' t FROM audit_log"
                        + " WHERE user_id = ? AND action_type = 'DATASET_ACCESS_DENIED'",
                    runner));
    assertThat(row).isNotNull();
    assertThat(row.get("a", String.class)).isEqualTo("PIPELINE");
    assertThat(row.get("t", String.class)).isEqualTo(secTable);
  }

  /**
   * 리터럴 안의 주석 기호 사이에 숨긴 참조(실측 우회 형태)도 실행 시점에 판정된다 — 러너가 실행기에 넘기는 바로 그 문자열을 판정하기 때문이다. 실행 이력의 오류
   * 메시지는 다른 사용자도 보므로 숨김 테이블 이름 없이 구분 불가 메시지만 남아야 한다.
   */
  @Test
  void run_literalHiddenReference_isDeniedWithIndistinguishableMessage() throws Exception {
    long editor = userAt("민감");
    String sql =
        "SELECT v FROM "
            + qualified(pubTable)
            + " WHERE v = '/*' OR EXISTS (SELECT 1 FROM "
            + qualified(secTable)
            + ") OR v = '*/'";
    long p = pipeline(editor, sql, null);
    long low = executionService.executePipeline(p, userAt("공개"));
    assertThat(waitForEnd(low)).isEqualTo("FAILED");
    String error = stepError(low);
    assertThat(error).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(error).doesNotContain(secTable);
    // 대조군: 같은 문자열이 자격 있는 실행 주체로는 실행된다(검증·파싱 단계 거부가 아님).
    assertThat(waitForEnd(executionService.executePipeline(p, userAt("민감"))))
        .isEqualTo("COMPLETED");
  }

  @Test
  void run_runnerOwnedTempOutput_isRaisedToInputLevel() throws Exception {
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v FROM " + qualified(secTable), null);
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    var temp =
        inTenantFixture(
            () ->
                dsl.select(DATASET.SECURITY_LEVEL_ID, DATASET.SECURITY_LEVEL_AUTO_RAISED_AT)
                    .from(DATASET)
                    .where(DATASET.TABLE_NAME.like("ptmp\\_" + p + "\\_%"))
                    .fetchSingle());
    assertThat(temp.value1()).isEqualTo(fx.levelId("민감"));
    assertThat(temp.value2()).isNotNull();
  }

  /**
   * 허용 목록 필요 등급(기밀) 입력 → TEMP 가 기밀로 오르고 실행 주체가 허용 목록에 들어간다. 다음 스텝이 {@code {{#1}}} 로 그 TEMP 를 실행 시점
   * 판정으로 읽어 COMPLETED 가 되는 것이 "고아 없음"(스펙 §4.5)의 실제 증거다.
   */
  @Test
  void run_allowlistLevelTemp_seedsRunAsUserSoNextStepCanRead() throws Exception {
    String topTable = m + "_top";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long runner = userAt("기밀");
    fx.grantUser(topId, runner);
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(topTable), null, null, null),
                new PipelineStepRequest(
                    "s2", null, "SQL", "SELECT v FROM {{#1}}", null, null, List.of("s1"))));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    long s1Temp =
        inTenantFixture(
            () ->
                dsl.select(DATASET.ID)
                    .from(DATASET)
                    .where(DATASET.TABLE_NAME.like("ptmp\\_" + p + "\\_s1%"))
                    .fetchSingle(DATASET.ID));
    assertThat(levelOf(s1Temp)).isEqualTo(fx.levelId("기밀"));
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchExists(
                        DATASET_ACCESS_GRANT,
                        DATASET_ACCESS_GRANT
                            .DATASET_ID
                            .eq(s1Temp)
                            .and(DATASET_ACCESS_GRANT.USER_ID.eq(runner)))))
        .isTrue();
  }

  /**
   * 같은 파이프라인을 서로 다른 기밀 자격 실행 주체(예: 수동 실행자와 트리거 생성자)가 차례로 돌린다. 두 번째 실행은 이미 기밀로 오른 TEMP 를 재사용하므로 상향이
   * 일어나지 않는다 — 상향 때만 시드하면 두 번째 실행 주체가 허용 목록에 없어 step 2({@code {{#1}}})가 거부된다(fix round 1).
   */
  @Test
  void run_reusedAllowlistTemp_seedsEachRunAsUser() throws Exception {
    String topTable = m + "_top2";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long first = userAt("기밀");
    long second = userAt("기밀");
    fx.grantUser(topId, first);
    fx.grantUser(topId, second);
    long p =
        pipeline(
            first,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(topTable), null, null, null),
                new PipelineStepRequest(
                    "s2", null, "SQL", "SELECT v FROM {{#1}}", null, null, List.of("s1"))));
    assertThat(waitForEnd(executionService.executePipeline(p, first))).isEqualTo("COMPLETED");
    assertThat(waitForEnd(executionService.executePipeline(p, second))).isEqualTo("COMPLETED");
    long s1Temp = tempOf(p, "s1");
    assertThat(hasUserGrant(s1Temp, first)).isTrue();
    assertThat(hasUserGrant(s1Temp, second)).isTrue();
  }

  /**
   * 새로 만든(빈) TEMP 는 입력 최대 등급으로 정확히 맞춘다 — 기본 등급('내부')보다 낮은 '공개'여도(스펙 §4.5). 그래야 '공개' 자격 실행 주체가 다음
   * 스텝에서 자기 TEMP 를 읽을 수 있다.
   */
  @Test
  void run_freshTempFromPublicInputs_isSetToPublicAndNextStepReads() throws Exception {
    long runner = userAt("공개");
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(pubTable), null, null, null),
                new PipelineStepRequest(
                    "s2", null, "SQL", "SELECT v FROM {{#1}}", null, null, List.of("s1"))));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(tempOf(p, "s1"))).isEqualTo(fx.levelId("공개"));
  }

  /**
   * 재실행에서 TEMP 는 스텝 출력(coalesce 폴백)으로 들어온다 — 그래도 러너 소유 TEMP 로 다뤄야 한다. 입력 등급이 오르면 재사용 TEMP 를 상향하고(지정
   * 출력처럼 하향 실패가 아니다), 입력 등급이 다시 내려가도 재사용 TEMP 는 낮추지 않는다(이전 데이터가 남아 있을 수 있다).
   */
  @Test
  void run_reusedTemp_isRaisedWhenInputRisesAndNeverLowered() throws Exception {
    String inTable = m + "_mov";
    long inId = table(inTable, "공개");
    insertRow(inTable, "x");
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v FROM " + qualified(inTable), null);
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "step");
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("공개"));

    setLevel(inId, "민감");
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("민감"));

    setLevel(inId, "공개");
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("민감"));
  }

  /**
   * 이전 실행(A)이 기밀로 올린 재사용 TEMP 에, 지금 입력은 민감뿐인 실행 주체 B 가 쓰려 한다 — 상향도 시드도 일어나지 않으므로 B 는 TEMP 를 볼 수 없다.
   * 쓰기 전에 거부되어야 하고 A 의 결과 행은 그대로 남아야 한다(fix round 2).
   */
  @Test
  void run_reusedTempAboveInputLevel_deniesRunAsWhoCannotSeeItAndKeepsRows() throws Exception {
    String inTable = m + "_hi";
    long inId = table(inTable, "기밀");
    insertRow(inTable, "a");
    long a = userAt("기밀");
    fx.grantUser(inId, a);
    long p = pipeline(a, "SELECT v FROM " + qualified(inTable), null);
    assertThat(waitForEnd(executionService.executePipeline(p, a))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "step");
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("기밀"));
    String tempTable =
        inTenantFixture(
            () ->
                dsl.select(DATASET.TABLE_NAME)
                    .from(DATASET)
                    .where(DATASET.ID.eq(temp))
                    .fetchSingle(DATASET.TABLE_NAME));
    assertThat(rowCount(tempTable)).isEqualTo(1);

    // 입력을 민감으로 내리고 행을 하나 더 넣는다 — B 의 쓰기가 일어나면 TEMP 행 수가 2 가 된다.
    setLevel(inId, "민감");
    insertRow(inTable, "b");
    long b = userAt("민감");
    long exec = executionService.executePipeline(p, b);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(rowCount(tempTable)).isEqualTo(1);
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("기밀"));
  }

  /**
   * 스텝 TEMP 식별자(originType=TEMP·sourcePipelineStepId)는 일반 생성 경로에서 위조할 수 없다. 위조할 수 있으면 남의 스텝 출력 폴백이
   * 공격자 데이터셋을 가리켜 실행 주체의 입력 행이 그리로 적재된다(fix round 2). 거부 후 실행하면 스텝 출력은 러너가 만든 ptmp TEMP 다.
   */
  @Test
  void forgedStepTemp_isRejectedAndCannotBecomeStepOutput() throws Exception {
    long victim = userAt("민감");
    long p = pipeline(victim, "SELECT v FROM " + qualified(secTable), null);
    long stepId =
        inTenantFixture(
            () ->
                dsl.fetchOne("SELECT id FROM pipeline_step WHERE pipeline_id = ?", p)
                    .get(0, Long.class));
    long attacker = userAt("공개");
    List<DatasetColumnRequest> cols =
        List.of(new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false));
    for (CreateDatasetRequest forged :
        List.of(
            new CreateDatasetRequest(
                m + "_f1", m + "_f1", null, null, "TABLE", "TEMP", cols, stepId),
            new CreateDatasetRequest(
                m + "_f2", m + "_f2", null, null, "TABLE", "DERIVED", cols, stepId),
            new CreateDatasetRequest(
                m + "_f3", m + "_f3", null, null, "TABLE", "TEMP", cols, null))) {
      assertThatThrownBy(() -> datasetService.createDataset(forged, attacker))
          .isInstanceOf(CodedApiException.class)
          .extracting(e -> ((CodedApiException) e).code())
          .isEqualTo("DATASET_ORIGIN_RESERVED");
    }
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchCount(
                        DATASET,
                        DATASET
                            .TABLE_NAME
                            .like(m + "\\_f%")
                            .or(DATASET.SOURCE_PIPELINE_STEP_ID.eq(stepId)))))
        .isZero();

    assertThat(waitForEnd(executionService.executePipeline(p, victim))).isEqualTo("COMPLETED");
    List<String> stepOutputs =
        inTenantFixture(
            () ->
                dsl.select(DATASET.TABLE_NAME)
                    .from(DATASET)
                    .where(DATASET.SOURCE_PIPELINE_STEP_ID.eq(stepId))
                    .fetch(DATASET.TABLE_NAME));
    assertThat(stepOutputs).hasSize(1);
    assertThat(stepOutputs.get(0)).startsWith("ptmp_" + p + "_");
  }

  /**
   * 스펙 §4.5 — 지정 출력이 입력보다 낮으면 실패가 아니라 자동 상향 + 상향 시각 + 감사이고, 적재는 된다. 등급 변경 이벤트(AUTO_RAISE)는 정확히
   * 1건(공통 결정 R2 — 발행은 DatasetSecurityService 한 곳).
   */
  @Test
  void run_explicitLowerOutput_isAutoRaisedAndWritten() throws Exception {
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v FROM " + qualified(secTable), lowOutId);
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("COMPLETED");
    assertThat(rowCount(m + "_low")).isEqualTo(1);
    assertThat(levelOf(lowOutId)).isEqualTo(fx.levelId("민감"));
    assertThat(autoRaisedAt(lowOutId)).isNotNull();
    assertThat(autoRaiseAuditCount(lowOutId)).isEqualTo(1);
    assertThat(levelEventsFor(lowOutId))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.cause()).isEqualTo(DatasetSecurityLevelChangedEvent.Cause.AUTO_RAISE);
              assertThat(e.toLevelId()).isEqualTo(fx.levelId("민감"));
            });
  }

  /** DML 스텝의 쓰기 대상도 입력보다 낮으면 자동 상향된다(PIPELINE_RUN 은 더 이상 쓰기 하향을 거부하지 않는다). 이벤트·감사는 1건. */
  @Test
  void run_dmlWriteToLowerTarget_isAutoRaised() throws Exception {
    long runner = userAt("민감");
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "dml",
                    null,
                    "SQL",
                    "INSERT INTO "
                        + qualified(m + "_low")
                        + " (v) SELECT v FROM "
                        + qualified(secTable),
                    lowOutId,
                    null,
                    null,
                    "APPEND")));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(lowOutId)).isEqualTo(fx.levelId("민감"));
    assertThat(autoRaisedAt(lowOutId)).isNotNull();
    assertThat(rowCount(m + "_low")).isEqualTo(1);
    assertThat(autoRaiseAuditCount(lowOutId)).isEqualTo(1);
    assertThat(levelEventsFor(lowOutId)).hasSize(1);
  }

  /**
   * SQL 스텝의 선언 입력(inputDatasetIds)도 전파 입력이다(스펙 §4.5 "∪ 선언 입력"). SQL 은 공개만 읽어도 선언 입력이 민감이면 TEMP 는
   * 민감.
   */
  @Test
  void run_declaredInputRaisesTempEvenIfSqlReadsOnlyPublic() throws Exception {
    long secId = tableId(secTable);
    long runner = userAt("민감");
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1",
                    null,
                    "SQL",
                    "SELECT v FROM " + qualified(pubTable),
                    null,
                    List.of(secId),
                    null)));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(tempOf(p, "s1"))).isEqualTo(fx.levelId("민감"));
  }

  /**
   * 선언 입력도 실행 주체가 VIEW 할 수 있어야 한다(fail-closed, 계획 결정 10) — SQL 은 공개만 읽어도, 볼 수 없는 데이터셋을 선언 입력으로 넣으면
   * 실행 전에 구분 불가 메시지로 실패하고 출력에 아무것도 쓰지 않는다. 대조군은 위
   * run_declaredInputRaisesTempEvenIfSqlReadsOnlyPublic(같은 형태, 볼 수 있는 실행 주체 → COMPLETED).
   */
  @Test
  void run_declaredHiddenInput_failsBeforeExecution() throws Exception {
    long secId = tableId(secTable);
    long editor = userAt("민감");
    long p =
        pipeline(
            editor,
            List.of(
                new PipelineStepRequest(
                    "s1",
                    null,
                    "SQL",
                    "SELECT v FROM " + qualified(pubTable),
                    lowOutId,
                    List.of(secId),
                    null)));
    long exec = executionService.executePipeline(p, userAt("공개"));
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(rowCount(m + "_low")).isZero();
    assertThat(levelOf(lowOutId)).isEqualTo(fx.levelId("공개"));
  }

  /**
   * 공통 결정 R4 — PYTHON 출력 = 실행 주체 자격 이하 & 허용 목록 아닌 최고 등급. '민감' 실행 주체 → '민감'. 스크립트 성패와 무관하게 쓰기 전에
   * 상향된다.
   */
  @Test
  void run_pythonOutput_isRaisedToHighestReadableLevel() throws Exception {
    long out = table(m + "_pyl", "공개");
    long runAs = pythonUserAt("민감");
    long p = pipeline(runAs, List.of(pythonStep(out)));
    waitForEnd(executionService.executePipeline(p, runAs));
    assertThat(levelOf(out)).isEqualTo(fx.levelId("민감"));
    assertThat(autoRaisedAt(out)).isNotNull();
  }

  /**
   * 공통 결정 R4 — ADMIN 과 같은 최상위 자격('기밀', 허용 목록 등급) 실행 주체라도 PYTHON 은 '기밀'을 읽을 수 없으므로 출력은 '민감'이다(실행 주체
   * 자격 등급 아님). 허용 목록 등급으로 가지 않으므로 실행 주체 시드도 없다.
   */
  @Test
  void run_pythonOutput_ofTopClearanceRunAs_excludesAllowlistLevel() throws Exception {
    long out = table(m + "_pya", "공개");
    long runAs = pythonUserAt("기밀");
    long p = pipeline(runAs, List.of(pythonStep(out)));
    waitForEnd(executionService.executePipeline(p, runAs));
    assertThat(levelOf(out)).isEqualTo(fx.levelId("민감"));
    assertThat(hasUserGrant(out, runAs)).isFalse();
  }

  private java.time.LocalDateTime autoRaisedAt(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.select(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT)
                .from(DATASET)
                .where(DATASET.ID.eq(datasetId))
                .fetchOne(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT));
  }

  private int autoRaiseAuditCount(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type ="
                        + " 'DATASET_SECURITY_LEVEL_AUTO_RAISE' AND resource_id = ?",
                    String.valueOf(datasetId))
                .get(0, Integer.class));
  }

  private List<DatasetSecurityLevelChangedEvent> levelEventsFor(long datasetId) {
    return levelEvents.stream().filter(e -> e.datasetId() == datasetId).toList();
  }

  /** 대조군: 입력과 같은 등급의 지정 출력에는 쓴다 — 위 FAILED 가 컬럼 불일치 등이 아니라 하향 판정 때문임을 보인다. */
  @Test
  void run_explicitSameLevelOutput_completes() throws Exception {
    String outTable = m + "_out";
    long outId = table(outTable, "민감");
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v FROM " + qualified(secTable), outId);
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(rowCount(outTable)).isEqualTo(1);
  }

  /**
   * 사용자 DML 스텝의 지정 출력도 실행 주체가 볼 수 있어야 한다 — REPLACE 는 그 출력을 DELETE 하는 선행 문장을 붙이므로, 판정 없이는 볼 수 없는
   * 데이터셋을 비울 수 있다.
   */
  @Test
  void run_dmlStepWithHiddenExplicitOutput_failsAndKeepsRows() throws Exception {
    long editor = userAt("민감");
    String outTable = m + "_hidout";
    long hiddenOut = table(outTable, "민감");
    insertRow(outTable, "keep");
    String dml = "INSERT INTO " + qualified(pubTable) + " (v) SELECT v FROM " + qualified(pubTable);
    long p = pipeline(editor, dml, hiddenOut);
    long exec = executionService.executePipeline(p, userAt("공개"));
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(rowCount(outTable)).isEqualTo(1);
    assertThat(rowCount(pubTable)).isEqualTo(1);
  }

  /** 지정 등급 자격 + PYTHON 실행 권한을 가진 사용자(러너가 실행 주체의 pipeline:python_execute 를 본다). */
  private long pythonUserAt(String level) {
    long uid = fx.createUser("pa_py");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "pa_py_r_" + System.nanoTime(),
            fx.levelId(level),
            "pipeline:read",
            "pipeline:write",
            "pipeline:execute",
            "pipeline:python_execute");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private static PipelineStepRequest pythonStep(Long outputDatasetId) {
    return new PipelineStepRequest(
        "step", null, "PYTHON", "print('x')", outputDatasetId, null, null, "REPLACE");
  }

  private static PipelineStepRequest apiStep(Long outputDatasetId) {
    return new PipelineStepRequest(
        "step",
        null,
        "API_CALL",
        null,
        outputDatasetId,
        null,
        null,
        "REPLACE",
        Map.of("customUrl", "http://127.0.0.1:9/never", "method", "GET", "dataPath", "$"),
        null,
        null,
        null);
  }

  /**
   * 코드리뷰 CR2 — API_CALL·PYTHON 스텝은 SQL 관문 없이 지정 출력을 비우고 덮어쓴다. 편집자가 볼 수 없는 데이터셋을 출력으로 지정한 스텝은 저장 시점에
   * SQL 스텝과 같은 403 으로 거부된다. 대조군: 볼 수 있는 편집자는 같은 스텝을 저장한다(거부가 다른 검증 때문이 아님).
   */
  @Test
  void save_apiCallOrPythonStepWithHiddenExplicitOutput_rejectedForEditor() {
    long secId = tableId(secTable);
    long low = pythonUserAt("공개");
    for (PipelineStepRequest step : List.of(pythonStep(secId), apiStep(secId))) {
      assertThatThrownBy(() -> pipeline(low, List.of(step)))
          .as(step.scriptType())
          .isInstanceOf(CodedApiException.class)
          .extracting(e -> ((CodedApiException) e).code())
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
      assertThat(pipeline(pythonUserAt("민감"), List.of(step))).isPositive();
    }
  }

  /**
   * 저장 판정은 <b>이 파이프라인의</b> 러너 TEMP 출력을 건너뛴다 — 편집 화면은 GET 의 출력 폴백(스텝 TEMP id)을 그대로 되돌려 보내므로, 등급이 오른
   * TEMP 를 판정하면 다른 편집자의 재저장이 막힌다. TEMP 쓰기는 실행 시점에 실행 주체 기준으로 판정된다.
   *
   * <p>Task 3 리뷰 M1: 예전 이 TC 는 <b>다른</b> 파이프라인을 새로 만들어 TEMP 를 되돌려 보냈다 — 그 형태가 곧 "다른 파이프라인의 숨김 TEMP
   * id 는 저장 성공·없는 id 는 403" 존재 오라클이라 이제
   * 거부된다(DatasetReferenceNameTest.pythonStep_hiddenTempOfOtherPipelineAsOutput_sameAsMissing). 편집
   * 화면의 실제 왕복인 "같은 파이프라인 재저장"으로 바꿨다.
   */
  @Test
  void save_pythonStepEchoingRaisedTempOutput_isNotJudgedAtSaveTime() throws Exception {
    long sens = userAt("민감");
    long p = pipeline(sens, "SELECT v FROM " + qualified(secTable), null);
    assertThat(waitForEnd(executionService.executePipeline(p, sens))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "step");
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("민감"));
    // 공개 자격 편집자가 같은 파이프라인을 PYTHON 스텝(출력 = GET 이 돌려준 TEMP 폴백)으로 재저장 — 판정 없이 통과해야 한다.
    pipelineService.updatePipeline(
        p,
        new com.smartfirehub.pipeline.dto.UpdatePipelineRequest(
            "PA " + m + " echo", "보안 등급 TC", true, List.of(pythonStep(temp))),
        pythonUserAt("공개"));
    // 정리 순서: 이제 스텝이 TEMP 를 출력으로 참조하므로 파이프라인을 먼저 지우고 TEMP 를 지운다(tearDown 은 TEMP 를 먼저 지운다).
    pipelineService.deletePipeline(p);
    pipelines.remove(Long.valueOf(p));
    datasetService.deleteDataset(temp);
  }

  /**
   * 코드리뷰 CR2 — 실행 시점: 실행 주체가 볼 수 없는 지정 출력에 PYTHON 스텝이 쓰지 못한다. 실행기 끈 REPLACE 는 스크립트 실행 전에 출력을
   * truncate 하므로, 관문이 없으면 스크립트 성패와 무관하게 숨김 데이터셋이 비워진다 — 행 수로 확인한다. 거부 메시지는 구분 불가 문구.
   */
  @Test
  void run_pythonStepWithHiddenExplicitOutput_failsAndKeepsRows() throws Exception {
    String outTable = m + "_pyout";
    long hiddenOut = table(outTable, "민감");
    insertRow(outTable, "keep");
    long p = pipeline(pythonUserAt("민감"), List.of(pythonStep(hiddenOut)));
    long runner = pythonUserAt("공개");
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(rowCount(outTable)).isEqualTo(1);
    // 코드리뷰 8: 거부가 트랜잭션(enforcePythonOutputLevel) 안에서 나도 거부 감사는 롤백되지 않고 정확히 1건(중복 판정 제거 후 이중 감사 없음).
    awaitSecurityAudit();
    int denials =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                            + " 'DATASET_ACCESS_DENIED' AND metadata ->> 'action' = 'PIPELINE'",
                        runner)
                    .get(0, Integer.class));
    assertThat(denials).isEqualTo(1);
  }

  /** API_CALL 도 같다 — 거부는 API 호출·REPLACE 맞바꿈 전에 구분 불가 메시지로 난다(호출 실패 메시지가 아니다). */
  @Test
  void run_apiCallStepWithHiddenExplicitOutput_isDeniedBeforeCall() throws Exception {
    String outTable = m + "_apiout";
    long hiddenOut = table(outTable, "민감");
    insertRow(outTable, "keep");
    long p = pipeline(userAt("민감"), List.of(apiStep(hiddenOut)));
    long exec = executionService.executePipeline(p, userAt("공개"));
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(rowCount(outTable)).isEqualTo(1);
  }

  /**
   * 러너 TEMP 를 만드는 PYTHON 스텝(출력 미지정 + outputColumns v). 실행기 끈 REPLACE 는 스크립트 실행 전에 출력을 truncate 한다.
   */
  private static PipelineStepRequest pythonTempStep() {
    return new PipelineStepRequest(
        "step",
        null,
        "PYTHON",
        "print('x')",
        null,
        null,
        null,
        "REPLACE",
        null,
        null,
        Map.of("outputColumns", List.of(Map.of("name", "v", "type", "TEXT"))),
        null);
  }

  /** 러너 TEMP 를 만드는 API_CALL 스텝(출력 미지정 + 필드 매핑 v). 호출은 닿지 않는 주소라 항상 호출 실패로 끝난다. */
  private static PipelineStepRequest apiTempStep() {
    return new PipelineStepRequest(
        "step",
        null,
        "API_CALL",
        null,
        null,
        null,
        null,
        "REPLACE",
        Map.of(
            "customUrl",
            "http://127.0.0.1:9/never",
            "method",
            "GET",
            "dataPath",
            "$",
            "fieldMappings",
            List.of(Map.of("sourceField", "v", "targetColumn", "v", "dataType", "TEXT"))),
        null,
        null,
        null);
  }

  /**
   * 후속 F1 — API_CALL·PYTHON 의 러너 TEMP 재사용에도 실행 주체 VIEW 판정이 있다. 재실행에서 TEMP 는 coalesce 폴백으로 "들어온 출력"이
   * 되는데, 예전 판정은 러너 TEMP 를 건너뛰어 볼 수 없는 실행 주체가 TEMP 를 비우고(실행기 끈 PYTHON REPLACE 는 실행 전 truncate) 덮어썼다.
   * 관리자가 TEMP 등급을 실행 주체 자격보다 높이면 다음 실행은 쓰기 전에 구분 불가 메시지로 실패하고 TEMP 는 그대로(같은 id·행 수·등급)여야 한다.
   *
   * <p>대조군: 등급을 올리기 전에는 같은 실행 주체의 재실행이 보안 판정으로 막히지 않는다(PYTHON 은 truncate 까지 도달해 행이 0 이 된다) — 실패가 다른
   * 이유가 아니라 등급 때문임을 보인다.
   */
  @Test
  void run_pythonReusedTempRaisedAboveRunAs_failsAndKeepsTemp() throws Exception {
    long a = pythonUserAt("민감");
    long b = pythonUserAt("민감");
    long p = pipeline(a, List.of(pythonTempStep()));
    waitForEnd(executionService.executePipeline(p, a));
    long temp = tempOf(p, "step");
    String tempTable = tableNameOf(temp);

    // 대조군: 등급을 올리기 전 B 의 재실행은 판정을 통과해 truncate 에 닿는다.
    insertRow(tempTable, "a1");
    long control = executionService.executePipeline(p, b);
    waitForEnd(control);
    assertThat(failedStepErrorOrNull(control))
        .isNotEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(rowCount(tempTable)).isZero();

    insertRow(tempTable, "keep");
    setLevel(temp, "기밀");
    long exec = executionService.executePipeline(p, b);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(tempOf(p, "step")).isEqualTo(temp);
    assertThat(rowCount(tempTable)).isEqualTo(1);
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("기밀"));
  }

  /**
   * 후속 F1 — API_CALL 도 같다. 호출 주소가 닿지 않아 실행은 어차피 실패하므로 행 수만으로는 판정 유무가 드러나지 않는다 — 실패 메시지가 호출 실패가 아니라
   * 구분 불가 거부 문구인지로 판정이 호출·맞바꿈 <b>전에</b> 났음을 확인한다(대조군은 같은 실행 주체가 호출 실패 메시지를 받는다).
   */
  @Test
  void run_apiCallReusedTempRaisedAboveRunAs_isDeniedBeforeCallAndKeepsTemp() throws Exception {
    long a = userAt("민감");
    long b = userAt("민감");
    long p = pipeline(a, List.of(apiTempStep()));
    waitForEnd(executionService.executePipeline(p, a));
    long temp = tempOf(p, "step");
    String tempTable = tableNameOf(temp);

    long control = executionService.executePipeline(p, b);
    assertThat(waitForEnd(control)).isEqualTo("FAILED");
    assertThat(stepError(control)).isNotEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);

    insertRow(tempTable, "keep");
    setLevel(temp, "기밀");
    long exec = executionService.executePipeline(p, b);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    assertThat(tempOf(p, "step")).isEqualTo(temp);
    assertThat(rowCount(tempTable)).isEqualTo(1);
    assertThat(levelOf(temp)).isEqualTo(fx.levelId("기밀"));
  }

  private String tableNameOf(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.select(DATASET.TABLE_NAME)
                .from(DATASET)
                .where(DATASET.ID.eq(datasetId))
                .fetchSingle(DATASET.TABLE_NAME));
  }

  /** 실행의 실패 스텝 오류 메시지 — 실패 스텝이 없으면(COMPLETED) null. */
  private String failedStepErrorOrNull(long executionId) {
    return inTenantFixture(
        () ->
            dsl.fetchOptional(
                    "SELECT error_message FROM pipeline_step_execution WHERE execution_id = ?"
                        + " AND status = 'FAILED'",
                    executionId)
                .map(r -> r.get(0, String.class))
                .orElse(null));
  }

  private long tableId(String t) {
    return inTenantFixture(
        () ->
            dsl.select(DATASET.ID)
                .from(DATASET)
                .where(DATASET.TABLE_NAME.eq(t))
                .fetchSingle(DATASET.ID));
  }

  @Test
  void run_trigger_judgesTriggerCreator() throws Exception {
    long editor = userAt("민감");
    long p = pipeline(editor, "SELECT v FROM " + qualified(secTable), null);
    long lowCreator = userAt("공개");
    long trig =
        triggerService
            .createTrigger(
                p, new CreateTriggerRequest("t-low", TriggerType.API, null, Map.of()), lowCreator)
            .id();
    triggerService.fireTrigger(trig, Map.of());
    assertThat(waitForEnd(latestExecution(p))).isEqualTo("FAILED");
    long highCreator = userAt("민감");
    long trig2 =
        triggerService
            .createTrigger(
                p, new CreateTriggerRequest("t-high", TriggerType.API, null, Map.of()), highCreator)
            .id();
    triggerService.fireTrigger(trig2, Map.of());
    assertThat(waitForEnd(latestExecution(p))).isEqualTo("COMPLETED");
  }

  /**
   * WD-30 — 러너 TEMP 의 허용 목록은 매 실행 "허용 목록 필요 입력들의 교집합 ∪ {실행 주체}" 로 다시 계산된다. 입력 목록에서 빠진 사람은 TEMP 에서도
   * 빠진다(예전: 늘어나기만 했다). 실행 주체는 남아 빈 목록(고아)이 생기지 않는다.
   */
  @Test
  void run_tempAllowlist_isRecomputedEachRun_andShrinks() throws Exception {
    String topTable = m + "_wd30";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long first = userAt("기밀");
    long second = userAt("기밀");
    fx.grantUser(topId, first);
    fx.grantUser(topId, second);
    long p =
        pipeline(
            first,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(topTable), null, null, null)));
    assertThat(waitForEnd(executionService.executePipeline(p, first))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");
    assertThat(hasUserGrant(temp, first)).isTrue();
    assertThat(hasUserGrant(temp, second)).isTrue();

    inTenantFixture(
        () ->
            dsl.deleteFrom(DATASET_ACCESS_GRANT)
                .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(topId))
                .and(DATASET_ACCESS_GRANT.USER_ID.eq(second))
                .execute());
    assertThat(waitForEnd(executionService.executePipeline(p, first))).isEqualTo("COMPLETED");
    assertThat(hasUserGrant(temp, second)).isFalse();
    assertThat(hasUserGrant(temp, first)).isTrue();
  }

  /**
   * WD-30 설계 결정 13 — 쓰기 <b>전</b> 좁히기는 쓰기가 실패해도 남는다. 입력 목록에서 빠진 사람은 실패한 재실행 뒤에도 TEMP(이전 실행 데이터가 남아
   * 있음)에서 빠져 있어야 한다. 쓰기 후 확정(completeOutputAllowlist)은 실패 경로에서 돌지 않으므로 이 TC 는 좁히기만 고정한다.
   */
  @Test
  void run_tempAllowlist_narrowedBeforeWrite_evenWhenStepFails() throws Exception {
    String topTable = m + "_wd30f";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "1");
    long first = userAt("기밀");
    long second = userAt("기밀");
    fx.grantUser(topId, first);
    fx.grantUser(topId, second);
    // 실행 시점에만 실패하는 SELECT — 가드·출력 등급 처리(좁히기)는 통과하고, 적재 중 형 변환이 실패한다.
    long p =
        pipeline(
            first,
            List.of(
                new PipelineStepRequest(
                    "s1",
                    null,
                    "SQL",
                    "SELECT v::int AS n FROM " + qualified(topTable),
                    null,
                    null,
                    null)));
    assertThat(waitForEnd(executionService.executePipeline(p, first))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");
    assertThat(hasUserGrant(temp, second)).isTrue();

    inTenantFixture(
        () ->
            dsl.deleteFrom(DATASET_ACCESS_GRANT)
                .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(topId))
                .and(DATASET_ACCESS_GRANT.USER_ID.eq(second))
                .execute());
    insertRow(topTable, "x");
    assertThat(waitForEnd(executionService.executePipeline(p, first))).isEqualTo("FAILED");
    assertThat(hasUserGrant(temp, second)).isFalse();
    assertThat(hasUserGrant(temp, first)).isTrue();
  }

  /**
   * 교집합은 항목 단위 — 입력 허용 목록의 역할 항목이 TEMP 에 그대로 들어가고, 입력에 새로 추가된 사람은 다음 실행에 TEMP 에도 들어간다(쓰기 후 확정이 넓힘까지
   * 맞춘다).
   */
  @Test
  void run_tempAllowlist_carriesRoleEntries_andGrowsWithInput() throws Exception {
    String topTable = m + "_wd30r";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long runner = userAt("기밀");
    long roleOnly = fx.createRole("pa_rr_" + System.nanoTime(), fx.levelId("기밀"), "dataset:read");
    roles.add(roleOnly);
    fx.grantUser(topId, runner);
    fx.grantRole(topId, roleOnly);
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(topTable), null, null, null)));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");
    assertThat(hasRoleGrant(temp, roleOnly)).isTrue();

    long late = userAt("기밀");
    fx.grantUser(topId, late);
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(hasUserGrant(temp, late)).isTrue();
    assertThat(hasRoleGrant(temp, roleOnly)).isTrue();
  }

  /**
   * 코드리뷰 CR1 — 같은 러너 TEMP 에 겹친 두 실행의 쓰기 순서와 확정 순서가 어긋나도 넓힘이 새지 않는다. 순서 "A 쓰기 → B 쓰기 → B 확정 → A 확정"
   * 을 게이트 호출 순서로 고정한다(쓰기는 TEMP 데이터만 바꾸므로 허용 목록 관점에서는 두 실행의 쓰기 전 좁히기 뒤 어느 시점이든 같다). TEMP 의 데이터는 마지막에
   * 쓴 B 의 것이므로 목록은 B 의 시드(B 입력 목록 ∩ ∪ {B})를 넘으면 안 된다 — A 입력에만 있는 제3자(outsider)와 A 가 들어오면 누출이다. 확정
   * 직전 겹침 판정(PipelineExecutionRepository#hasOverlappingStepExecution)을 지우면 A 의 확정이 A 시드로 넓혀 실패한다.
   */
  @Test
  void run_tempAllowlist_overlappingRuns_doNotWidenOverOtherRunsWrite() throws Exception {
    String aTable = m + "_cr1a";
    long aIn = table(aTable, "기밀");
    insertRow(aTable, "a");
    long bIn = table(m + "_cr1b", "기밀");
    long runA = userAt("기밀");
    long runB = userAt("기밀");
    long outsider = userAt("기밀");
    fx.grantUser(aIn, runA);
    fx.grantUser(aIn, runB);
    fx.grantUser(aIn, outsider);
    fx.grantUser(bIn, runB);
    // 첫 실행(겹침 없음)으로 러너 TEMP 를 만든다 — 목록 = A 입력 목록 {A, B, outsider}.
    long p =
        pipeline(
            runA,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(aTable), null, null, null)));
    assertThat(waitForEnd(executionService.executePipeline(p, runA))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");
    long stepId = stepOf(p);
    assertThat(hasUserGrant(temp, outsider)).isTrue();

    LevelPolicy top = levelRepository.findById(fx.levelId("기밀")).orElseThrow();
    long execA = runningStepExecution(p, runA, stepId);
    long execB = runningStepExecution(p, runB, stepId);
    // 두 실행의 쓰기 전 처리(좁히기) — A 다음 B. 둘 다 출력 전부 교체(REPLACE)라 확정 계획이 나온다.
    PipelineSecurityGate.OutputAllowlistPlan planA =
        pipelineSecurityGate.enforceOutputLevel(
            new SqlAccessResult(true, null, null, top, Set.of(aIn), Set.of(), true),
            temp,
            stepId,
            false,
            true,
            pipelineSecurityGate.runAs(runA));
    PipelineSecurityGate.OutputAllowlistPlan planB =
        pipelineSecurityGate.enforceOutputLevel(
            new SqlAccessResult(true, null, null, top, Set.of(bIn), Set.of(), true),
            temp,
            stepId,
            false,
            true,
            pipelineSecurityGate.runAs(runB));
    assertThat(planA).isNotNull();
    assertThat(planB).isNotNull();
    // (A 쓰기 → B 쓰기) 뒤 B 가 먼저 확정한다 — A 는 아직 RUNNING.
    pipelineSecurityGate.completeOutputAllowlist(planB, execB);
    executionRepository.updateStepExecution(
        execB, "COMPLETED", null, null, null, null, LocalDateTime.now(ZoneOffset.UTC));
    // A 가 나중에 확정한다 — B 가 A 시작 뒤에 끝났으므로 겹침.
    pipelineSecurityGate.completeOutputAllowlist(planA, execA);

    assertThat(hasUserGrant(temp, outsider)).isFalse();
    assertThat(hasUserGrant(temp, runA)).isFalse();
    assertThat(hasUserGrant(temp, runB)).isTrue();
  }

  /**
   * 코드리뷰 CR1 — 쓰기 후 확정(겹침 판정 + 시드로 맞춤)과 다른 실행의 쓰기 전 좁히기는 데이터셋 행 잠금으로 직렬화된다. 확정 트랜잭션을 커밋 전에 멈추면 좁히기가
   * 기다려야 하고(secondBlocked), 기다린 뒤에는 확정이 넣은 항목까지 보고 좁힌다. 잠금이 없으면 좁히기가 확정 전 목록을 읽고 끝나 확정이 넣은 outsider
   * 가 좁히기 뒤(그 실행의 데이터 위)에 남는다. 어느 쪽 FOR UPDATE 를 지워도 실패한다.
   */
  @Test
  void completeOutputAllowlist_serializesWithOtherRunsNarrowing() throws Exception {
    String aTable = m + "_cr1l";
    long aIn = table(aTable, "기밀");
    insertRow(aTable, "a");
    long runA = userAt("기밀");
    long runB = userAt("기밀");
    long outsider = userAt("기밀");
    fx.grantUser(aIn, runA);
    long p =
        pipeline(
            runA,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(aTable), null, null, null)));
    assertThat(waitForEnd(executionService.executePipeline(p, runA))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");
    long stepId = stepOf(p);
    // 입력 목록이 넓어졌다 — 다음(겹치지 않은) 실행 A 의 확정은 outsider 를 넣는다.
    fx.grantUser(aIn, outsider);
    LevelPolicy top = levelRepository.findById(fx.levelId("기밀")).orElseThrow();
    long execA = runningStepExecution(p, runA, stepId);
    PipelineSecurityGate.OutputAllowlistPlan planA =
        pipelineSecurityGate.enforceOutputLevel(
            new SqlAccessResult(true, null, null, top, Set.of(aIn), Set.of(), true),
            temp,
            stepId,
            false,
            true,
            pipelineSecurityGate.runAs(runA));
    assertThat(planA).isNotNull();

    PausedTransactionRace.Outcome<Boolean> race =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> pipelineSecurityGate.completeOutputAllowlist(planA, execA),
            () -> {
              // B 의 쓰기 전 좁히기(시드 {B}) — B 가 이어서 쓸 데이터 위의 목록 상한이다.
              datasetSecurityService.narrowPipelineOutputAllowlist(
                  temp, Set.of(GrantSubject.user(runB)), runB);
              return true;
            });

    assertThat(race.secondError()).isNull();
    assertThat(race.secondBlocked()).isTrue();
    assertThat(hasUserGrant(temp, outsider)).isFalse();
    assertThat(hasUserGrant(temp, runA)).isFalse();
    assertThat(hasUserGrant(temp, runB)).isTrue();
  }

  /** 단일 스텝 파이프라인의 스텝 id. */
  private long stepOf(long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.select(PIPELINE_STEP.ID)
                .from(PIPELINE_STEP)
                .where(PIPELINE_STEP.PIPELINE_ID.eq(pipelineId))
                .fetchSingle(PIPELINE_STEP.ID));
  }

  /** 러너가 executeStep 첫머리에서 하듯 RUNNING(started_at=지금)으로 표시한 스텝 실행 행을 만든다(CR1 겹침 판정의 입력). */
  private long runningStepExecution(long pipelineId, long executedBy, long stepId) {
    long exec = executionRepository.createExecution(pipelineId, executedBy);
    long stepExec = executionRepository.createStepExecution(exec, stepId);
    executionRepository.updateStepExecution(
        stepExec, "RUNNING", null, null, null, LocalDateTime.now(ZoneOffset.UTC), null);
    return stepExec;
  }

  /**
   * 리뷰 I1 — APPEND 러너 TEMP 는 이전 실행 행이 남으므로 입력 허용 목록이 넓어져도 TEMP 목록은 넓어지지 않는다(쓰기 후 확정 없음, 좁히기만). 넓히면
   * 이전 실행 행이 그 실행 때 입력을 볼 수 없던 사람에게 보인다.
   */
  @Test
  void run_appendTemp_isNotWidenedByLaterInputGrant() throws Exception {
    String topTable = m + "_wd30a";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long runner = userAt("기밀");
    fx.grantUser(topId, runner);
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1",
                    null,
                    "SQL",
                    "SELECT v FROM " + qualified(topTable),
                    null,
                    null,
                    null,
                    "APPEND")));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");

    long late = userAt("기밀");
    fx.grantUser(topId, late);
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(hasUserGrant(temp, late)).isFalse();
    assertThat(hasUserGrant(temp, runner)).isTrue();
  }

  /**
   * 리뷰 I1(최종 수정) — 증분 APPEND 러너 TEMP 라도 전체 재생성 예약(stepWasFullRebuild)으로 출력을 통째로 비우고 다시 채운 실행은 이전 실행
   * 행이 남지 않으므로 쓰기 후 확정이 넓힘까지 맞춘다: 입력에 늦게 추가된 사람이 TEMP 에도 들어간다. 대조군은 위
   * run_appendTemp_isNotWidenedByLaterInputGrant (예약 없는 APPEND 는 넓히지 않음).
   */
  @Test
  void run_incrementalFullRebuildTemp_isWidenedByLaterInputGrant() throws Exception {
    String topTable = m + "_wd30fr";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long runner = userAt("기밀");
    fx.grantUser(topId, runner);
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1",
                    null,
                    "SQL",
                    "SELECT v FROM "
                        + qualified(topTable)
                        + " WHERE _updated_at >= {{last_run_at}}",
                    null,
                    null,
                    null,
                    "APPEND")));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    long temp = tempOf(p, "s1");

    long late = userAt("기밀");
    fx.grantUser(topId, late);
    // 전체 재생성 예약 — 다음 실행은 책갈피를 무시하고 출력을 비운 뒤 전체를 다시 채운다(PipelineAsyncRunner 의 DELETE 선행 문장).
    long stepId =
        inTenantFixture(
            () ->
                dsl.select(PIPELINE_STEP.ID)
                    .from(PIPELINE_STEP)
                    .where(PIPELINE_STEP.PIPELINE_ID.eq(p))
                    .fetchSingle(PIPELINE_STEP.ID));
    pipelineService.setFullRebuildPending(p, stepId, true);
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(hasUserGrant(temp, late)).isTrue();
    assertThat(hasUserGrant(temp, runner)).isTrue();
    // 출력이 실제로 통째로 교체됐다(전체 재구축) — 입력 1행이 두 번 쌓이지 않고 1행.
    assertThat(
            rowCount(
                inTenantFixture(
                    () ->
                        dsl.select(DATASET.TABLE_NAME)
                            .from(DATASET)
                            .where(DATASET.ID.eq(temp))
                            .fetchSingle(DATASET.TABLE_NAME))))
        .isEqualTo(1);
  }

  /**
   * 리뷰 M4 — 사용자 지정 출력이 허용 목록 등급으로 상향되면 기존 목록을 시드로 좁힐 뿐 넓히지 않는다(기존 ∩ 시드 ∪ {실행 주체}): 입력 목록에만 있는 사람은
   * 들어오지 않고, 입력 목록에 없는 기존 항목은 빠진다.
   */
  @Test
  void run_designatedOutputRaisedToAllowlist_narrowsExistingList_neverWidens() throws Exception {
    String topTable = m + "_wd30i";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    long outId = table(m + "_wd30o", "공개");
    long runner = userAt("기밀");
    long inputOnly = userAt("기밀");
    long outputOnly = userAt("기밀");
    fx.grantUser(topId, runner);
    fx.grantUser(topId, inputOnly);
    fx.grantUser(outId, outputOnly);
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "s1", null, "SQL", "SELECT v FROM " + qualified(topTable), outId, null, null)));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(outId)).isEqualTo(fx.levelId("기밀"));
    assertThat(hasUserGrant(outId, runner)).isTrue();
    assertThat(hasUserGrant(outId, inputOnly)).isFalse();
    assertThat(hasUserGrant(outId, outputOnly)).isFalse();
  }

  /** 리뷰 M4 — DML 쓰기 대상이 허용 목록 등급으로 상향될 때도 같은 좁히기(넓히지 않음)를 한다. */
  @Test
  void run_dmlWriteTargetRaisedToAllowlist_narrowsExistingList_neverWidens() throws Exception {
    String topTable = m + "_wd30j";
    long topId = table(topTable, "기밀");
    insertRow(topTable, "t");
    String dmlTable = m + "_wd30d";
    long dmlId = table(dmlTable, "공개");
    long runner = userAt("기밀");
    long inputOnly = userAt("기밀");
    long outputOnly = userAt("기밀");
    fx.grantUser(topId, runner);
    fx.grantUser(topId, inputOnly);
    fx.grantUser(dmlId, outputOnly);
    long p =
        pipeline(
            runner,
            List.of(
                new PipelineStepRequest(
                    "dml",
                    null,
                    "SQL",
                    "INSERT INTO "
                        + qualified(dmlTable)
                        + " (v) SELECT v FROM "
                        + qualified(topTable),
                    dmlId,
                    null,
                    null,
                    "APPEND")));
    assertThat(waitForEnd(executionService.executePipeline(p, runner))).isEqualTo("COMPLETED");
    assertThat(levelOf(dmlId)).isEqualTo(fx.levelId("기밀"));
    assertThat(hasUserGrant(dmlId, runner)).isTrue();
    assertThat(hasUserGrant(dmlId, inputOnly)).isFalse();
    assertThat(hasUserGrant(dmlId, outputOnly)).isFalse();
  }

  private boolean hasRoleGrant(long datasetId, long roleId) {
    return inTenantFixture(
        () ->
            dsl.fetchExists(
                DATASET_ACCESS_GRANT,
                DATASET_ACCESS_GRANT
                    .DATASET_ID
                    .eq(datasetId)
                    .and(DATASET_ACCESS_GRANT.ROLE_ID.eq(roleId))));
  }

  private String qualified(String table) {
    return DataSchema.qualify(table);
  }

  /** 러너가 만든 스텝 TEMP(ptmp_<pipelineId>_<step>…)의 데이터셋 id. */
  private long tempOf(long pipelineId, String stepName) {
    return inTenantFixture(
        () ->
            dsl.select(DATASET.ID)
                .from(DATASET)
                .where(DATASET.TABLE_NAME.like("ptmp\\_" + pipelineId + "\\_" + stepName + "%"))
                .fetchSingle(DATASET.ID));
  }

  private boolean hasUserGrant(long datasetId, long userId) {
    return inTenantFixture(
        () ->
            dsl.fetchExists(
                DATASET_ACCESS_GRANT,
                DATASET_ACCESS_GRANT
                    .DATASET_ID
                    .eq(datasetId)
                    .and(DATASET_ACCESS_GRANT.USER_ID.eq(userId))));
  }

  private long levelOf(long datasetId) {
    return inTenantFixture(
        () ->
            dsl.select(DATASET.SECURITY_LEVEL_ID)
                .from(DATASET)
                .where(DATASET.ID.eq(datasetId))
                .fetchSingle(DATASET.SECURITY_LEVEL_ID));
  }

  private long latestExecution(long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT max(id) FROM pipeline_execution WHERE pipeline_id = ?", pipelineId)
                .get(0, Long.class));
  }

  /** 실행의 (실패한) 스텝 오류 메시지 — 단일 스텝 파이프라인 전용. */
  private String stepError(long executionId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT error_message FROM pipeline_step_execution WHERE execution_id = ?"
                        + " AND status = 'FAILED'",
                    executionId)
                .get(0, String.class));
  }

  /** PipelineUserDmlReplaceOutputLockTest#waitForEnd 와 같은 폴링(최대 60초). */
  private String waitForEnd(Long executionId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    String status = null;
    while (System.currentTimeMillis() < deadline) {
      status =
          inTenantFixture(
              () ->
                  dsl.fetchOne("SELECT status FROM pipeline_execution WHERE id = ?", executionId)
                      .get(0, String.class));
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        break;
      }
      Thread.sleep(100);
    }
    return status;
  }
}
