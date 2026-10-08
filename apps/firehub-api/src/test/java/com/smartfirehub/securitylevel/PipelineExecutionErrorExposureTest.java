package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.service.PipelineExecutionService;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-27(Task 4) 재현 TC: 실행 주체(민감 자격)가 돌린 파이프라인 스텝이 실행 중 실패하면, 그 실행 기록을 {@code pipeline:read} 만 가진 다른
 * 사용자(공개 자격 — 숨김 데이터셋을 못 봄)도 읽는다. 이 TC 는 그 조회자가 받는 실행 기록·파이프라인 상세에 숨김 데이터셋의 테이블명·행 값이 실리는지를 실측한다.
 */
@AutoConfigureMockMvc
class PipelineExecutionErrorExposureTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> pipelines = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String m;
  private long owner;
  private String secTable;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "ee" + System.nanoTime();
    owner = fx.createUser("ee_owner");
    users.add(owner);
    secTable = m + "_sec";
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    secTable,
                    secTable,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                owner)
            .id();
    datasets.add(id);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId("민감"))
                .where(DATASET.ID.eq(id))
                .execute());
    // 숨김 데이터셋의 행 값 — 오류 문구에 값이 실리는지 보기 위한 표지.
    inTenantFixture(
        () ->
            dsl.execute(
                "INSERT INTO " + DataSchema.qualify(secTable) + " (v) VALUES (?)",
                "SECRETVALUE42"));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long p : pipelines) {
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

  /** 기본 USER 역할을 떼고 지정 등급 자격의 역할 하나만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("ee_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "ee_r_" + System.nanoTime(),
            fx.levelId(level),
            "pipeline:read",
            "pipeline:write",
            "pipeline:execute");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "ee" + uid, DEFAULT_TEST_TENANT_ID);
  }

  private JsonNode getJson(String url, long viewer) throws Exception {
    MockHttpServletResponse r =
        mockMvc.perform(get(url).header("Authorization", token(viewer))).andReturn().getResponse();
    r.setCharacterEncoding("UTF-8");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return om.readTree(r.getContentAsString());
  }

  private long pipeline(long editor, String sql) {
    return pipelineOf(
        editor, new PipelineStepRequest("step", null, "SQL", sql, null, null, null, "REPLACE"));
  }

  private long pipelineOf(long editor, PipelineStepRequest... steps) {
    long id =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "EE " + m + " " + System.nanoTime(), "WD-27 TC", List.of(steps)),
                editor)
            .id();
    pipelines.add(id);
    return id;
  }

  /**
   * 실행 중(관문 통과 후) PG 가 거부하는 SELECT 스텝 — 오류 원문에 숨김 테이블명과 <b>행 값</b>이 실린다. 러너 TEMP 출력은 입력 등급(민감)으로
   * 올라가 있으므로 출력 VIEW 판정이 가린다.
   */
  @Test
  void selectStepRuntimeFailure_rawErrorWithheldFromViewerWhoCannotSeeInput() throws Exception {
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v::int AS n FROM " + DataSchema.qualify(secTable));
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertWithheldForLowButRawForCleared(p, exec);
  }

  /** 출력 데이터셋 없이 숨김 테이블을 직접 고치는 DML 스텝 — 출력 판정이 없으므로 SQL 참조 판정이 가려야 한다(출력 판정만으로는 새는 경로). */
  @Test
  void dmlStepWithoutOutputRuntimeFailure_rawErrorWithheldFromViewerWhoCannotSeeTarget()
      throws Exception {
    long runner = userAt("민감");
    String t = DataSchema.qualify(secTable);
    long p = pipeline(runner, "UPDATE " + t + " SET v = ((v)::int + 1)::text");
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertWithheldForLowButRawForCleared(p, exec);
  }

  /**
   * Fix round 1 규칙 1: 판정 근거가 없는 스텝(출력 미지정 PYTHON — 데이터셋 판정 대상이 없다)의 원문 오류는 실행 주체 본인과 테넌트 관리자(시스템
   * ADMIN 역할)에게만 보이고, 같은 자격의 다른 조회자에게는 가려진다. 실행 주체에게 python_execute 권한이 없어 러너가 실행 전에 실패시키므로 Python
   * 실행 환경 없이 결정적으로 실패 기록을 만든다.
   */
  @Test
  void stepWithoutJudgeableDatasets_rawErrorOnlyForRunAsAndAdmin() throws Exception {
    long runner = userAt("공개");
    long p =
        pipelineOf(
            runner,
            new PipelineStepRequest("py", null, "PYTHON", "print(1)", null, null, null, "APPEND"));
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    String url = "/api/v1/pipelines/" + p + "/executions/" + exec;

    // 실행 주체 본인 — 원문.
    assertThat(stepErrorFor(url, runner)).contains("pipeline:python_execute");
    // 테넌트 관리자(시스템 ADMIN 역할) — 원문.
    long admin = fx.createUser("ee_admin");
    users.add(admin);
    fx.assignRole(admin, adminRoleId());
    assertThat(stepErrorFor(url, admin)).contains("pipeline:python_execute");
    // 같은 자격의 다른 조회자 — 고정 문구.
    assertThat(stepErrorFor(url, userAt("공개")))
        .isEqualTo(PipelineService.WITHHELD_STEP_ERROR_MESSAGE);
  }

  /**
   * Fix round 2 I-1: {@code {{#1}}} 만 참조하고 출력이 없는 DML 스텝. 저장 모드 SQL 판정은 스텝 참조 더미를 빼므로, 참조 스텝(스텝1)의
   * TEMP(민감으로 상향됨)를 따로 VIEW 판정해야 행 값이 새지 않는다.
   */
  @Test
  void dmlOnStepReferenceWithoutOutput_rawErrorWithheldFromViewerWhoCannotSeeReferencedTemp()
      throws Exception {
    long runner = userAt("민감");
    long p =
        pipelineOf(
            runner,
            firstStepReadingSecret(),
            dependentSqlStep("UPDATE {{#1}} SET v = ((v)::int + 1)::text"));
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    String url = "/api/v1/pipelines/" + p + "/executions/" + exec;
    assertThat(stepErrorByName(url, userAt("공개"), "s2"))
        .isEqualTo(PipelineService.WITHHELD_STEP_ERROR_MESSAGE);
    // 양성 대조: 민감 자격 조회자(실행 주체 아님)에게는 행 값이 실린 원문.
    assertThat(stepErrorByName(url, userAt("민감"), "s2")).contains("SECRETVALUE42");
  }

  /**
   * Fix round 2 I-1 변형: 첫 실행에서 {@code {{#1}}} 을 읽는 SELECT 스텝이 컬럼 probe 단계에서 실패하면 그 스텝의 TEMP 가 아직 없어
   * 출력 판정이 없다 — 참조 스텝 TEMP 판정이 가려야 한다(숨김 컬럼명·ptmp 이름 노출 차단).
   */
  @Test
  void probeFailureOnFirstRun_rawErrorWithheldFromViewerWhoCannotSeeReferencedTemp()
      throws Exception {
    long runner = userAt("민감");
    long p =
        pipelineOf(
            runner,
            firstStepReadingSecret(),
            dependentSqlStep("SELECT nonexistent_col FROM {{#1}}"));
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    String url = "/api/v1/pipelines/" + p + "/executions/" + exec;
    assertThat(stepErrorByName(url, userAt("공개"), "s2"))
        .isEqualTo(PipelineService.WITHHELD_STEP_ERROR_MESSAGE);
    assertThat(stepErrorByName(url, userAt("민감"), "s2")).contains("nonexistent_col");
  }

  /**
   * Fix round 2 M-1: 실행 단위 오류(스텝 밖 최상위 예외 — 러너 경로로 결정적으로 만들기 어려워 저장값을 픽스처로 넣는다)도 조회자가 못 보는 스텝이 있으면
   * 가려진다.
   */
  @Test
  void executionLevelError_withheldWhenViewerCannotSeeAStep() throws Exception {
    long runner = userAt("민감");
    long p = pipeline(runner, "SELECT v::int AS n FROM " + DataSchema.qualify(secTable));
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    inTenantFixture(
        () ->
            dsl.execute(
                "UPDATE pipeline_execution SET error_message = ? WHERE id = ?",
                "EXEC-LEVEL SECRETVALUE42",
                exec));
    String url = "/api/v1/pipelines/" + p + "/executions/" + exec;
    assertThat(getJson(url, userAt("공개")).get("errorMessage").asText())
        .isEqualTo(PipelineService.WITHHELD_STEP_ERROR_MESSAGE);
    assertThat(getJson(url, userAt("민감")).get("errorMessage").asText())
        .isEqualTo("EXEC-LEVEL SECRETVALUE42");
  }

  /** 스텝1: 숨김(민감) 테이블을 읽어 러너 TEMP 로 적재 — TEMP 는 민감으로 오른다. */
  private PipelineStepRequest firstStepReadingSecret() {
    return new PipelineStepRequest(
        "s1",
        null,
        "SQL",
        "SELECT v FROM " + DataSchema.qualify(secTable),
        null,
        null,
        null,
        "REPLACE");
  }

  /** 스텝2: 스텝1 에 의존하고 출력 데이터셋을 지정하지 않은 SQL 스텝. */
  private PipelineStepRequest dependentSqlStep(String sql) {
    return new PipelineStepRequest("s2", null, "SQL", sql, null, null, List.of("s1"), "APPEND");
  }

  private String stepErrorByName(String url, long viewer, String stepName) throws Exception {
    for (JsonNode se : getJson(url, viewer).get("stepExecutions")) {
      if (stepName.equals(se.get("stepName").asText())) {
        return se.get("errorMessage").asText();
      }
    }
    throw new AssertionError("스텝 실행 없음: " + stepName);
  }

  private String stepErrorFor(String url, long viewer) throws Exception {
    return getJson(url, viewer).get("stepExecutions").get(0).get("errorMessage").asText();
  }

  private long adminRoleId() {
    return inTenantFixture(
        () ->
            dsl.select(field(name("role", "id"), Long.class))
                .from(table(name("role")))
                .where(field(name("role", "name"), String.class).eq("ADMIN"))
                .fetchSingle(field(name("role", "id"), Long.class)));
  }

  /**
   * 공개 자격 조회자에게는 스텝 오류가 고정 문구(테이블명·행 값 없음)이고, 민감 자격 조회자(실행 주체 아님)에게는 원문이 그대로다 — 양성 대조가 있어야 "원문이 애초에
   * 값을 싣지 않았다"는 공허한 통과를 배제한다.
   */
  private void assertWithheldForLowButRawForCleared(long p, long exec) throws Exception {
    String url = "/api/v1/pipelines/" + p + "/executions/" + exec;
    JsonNode lowStep = getJson(url, userAt("공개")).get("stepExecutions").get(0);
    assertThat(lowStep.get("status").asText()).isEqualTo("FAILED");
    assertThat(lowStep.get("errorMessage").asText())
        .isEqualTo(PipelineService.WITHHELD_STEP_ERROR_MESSAGE)
        .doesNotContain(secTable)
        .doesNotContain("SECRETVALUE42");
    assertThat(lowStep.get("log").isNull()).isTrue();

    String raw =
        getJson(url, userAt("민감")).get("stepExecutions").get(0).get("errorMessage").asText();
    assertThat(raw).contains("SECRETVALUE42").contains(secTable);
  }

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
