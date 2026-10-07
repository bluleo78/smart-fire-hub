package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
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
import com.smartfirehub.pipeline.service.PipelineExecutionService;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

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

  @Test
  void run_explicitLowerOutput_failsWithoutWriting() throws Exception {
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v FROM " + qualified(secTable), lowOutId);
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(rowCount(m + "_low")).isZero();
    assertThat(stepError(exec)).contains("더 낮은 등급");
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

  private String qualified(String table) {
    return DataSchema.qualify(table);
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
