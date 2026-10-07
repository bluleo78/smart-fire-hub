package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.service.PipelineExecutionService;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 최종 리뷰 C3: AI_CLASSIFY 스텝은 SQL 이 아니라 입력 데이터셋 테이블을 전부 읽어 LLM 으로 보낸다. SQL 스텝과 같은 의미로 저장 시 편집자, 실행 시
 * 실행 주체가 입력을 볼 수 있어야 하고, 출력은 SQL SELECT 스텝과 같은 등급 규칙(러너 TEMP 상향, 지정 출력 하향 실패)을 따른다.
 *
 * <p>실행기(AiClassifyExecutor)는 LLM 호출을 피하려고 목으로 둔다 — 단언의 핵심은 "거부된 실행은 실행기(=입력 읽기)에 도달하지 않는다"이므로 목 호출
 * 여부가 곧 증거다. 거부마다 자격 있는 실행 주체로는 실행기에 도달해 COMPLETED 가 되는 대조군을 둔다.
 */
class AiClassifyInputAccessTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @MockitoBean private AiClassifyExecutor aiClassifyExecutor;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> pipelines = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String m;
  private long owner;
  private long secId;
  private long lowOutId;

  @BeforeEach
  void setUp() {
    reset(aiClassifyExecutor);
    when(aiClassifyExecutor.execute(any(), any(), any()))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(1, "ok"));
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "aci" + System.nanoTime();
    owner = fx.createUser("aci_owner");
    users.add(owner);
    secId = table(m + "_sec", "민감");
    lowOutId = table(m + "_low", "공개");
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
      temps.forEach(this::quietDeleteDataset);
      try {
        pipelineService.deletePipeline(p);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다.
      }
    }
    datasets.forEach(this::quietDeleteDataset);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private void quietDeleteDataset(long id) {
    try {
      datasetService.deleteDataset(id);
    } catch (Exception ignored) {
      // 정리 실패는 다음 정리에 맡긴다.
    }
  }

  /** 데이터셋을 만들고 등급을 직접 지정한다. */
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

  /** 기본 USER 역할을 떼고 지정 등급 자격 + 파이프라인·AI 실행 권한만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("aci_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "aci_r_" + System.nanoTime(),
            fx.levelId(level),
            "pipeline:read",
            "pipeline:write",
            "pipeline:execute",
            "pipeline:ai_execute");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private long aiPipeline(long editor, long inputId, Long outputId) {
    Map<String, Object> aiConfig =
        Map.of("prompt", "분류", "outputColumns", List.of(Map.of("name", "label", "type", "TEXT")));
    long id =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "ACI " + m + " " + System.nanoTime(),
                    "보안 등급 TC",
                    List.of(
                        new PipelineStepRequest(
                            "ai",
                            null,
                            "AI_CLASSIFY",
                            null,
                            outputId,
                            List.of(inputId),
                            null,
                            "REPLACE",
                            null,
                            aiConfig,
                            null,
                            null))),
                editor)
            .id();
    pipelines.add(id);
    return id;
  }

  @Test
  void save_hiddenInput_rejectedForEditor() {
    assertThatThrownBy(() -> aiPipeline(userAt("공개"), secId, null))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    // 대조군: 입력을 볼 수 있는 편집자는 저장된다(다른 검증 실패가 아님).
    assertThat(aiPipeline(userAt("민감"), secId, null)).isPositive();
  }

  @Test
  void run_hiddenInput_failsBeforeReadingAndBeforeTempCreation() throws Exception {
    long p = aiPipeline(userAt("민감"), secId, null);
    long low = executionService.executePipeline(p, userAt("공개"));
    assertThat(waitForEnd(low)).isEqualTo("FAILED");
    assertThat(stepError(low)).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    verify(aiClassifyExecutor, never()).execute(any(), any(), any());
    // 판정이 TEMP 생성보다 먼저다 — 거부된 실행은 기본 등급 출력 TEMP 를 남기지 않는다.
    assertThat(tempLevel(p)).isNull();

    // 대조군: 자격 있는 실행 주체는 실행기에 도달하고, 새 러너 TEMP 는 입력 등급(민감)으로 맞춰진다.
    assertThat(waitForEnd(executionService.executePipeline(p, userAt("민감"))))
        .isEqualTo("COMPLETED");
    verify(aiClassifyExecutor, times(1)).execute(any(), any(), any());
    assertThat(tempLevel(p)).isEqualTo(fx.levelId("민감"));
  }

  @Test
  void run_explicitLowerOutput_failsWithDowngradeBeforeExecutor() throws Exception {
    long runner = userAt("민감");
    long p = aiPipeline(runner, secId, lowOutId);
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    assertThat(stepError(exec)).contains("더 낮은 등급 데이터셋에 쓸 수 없습니다");
    verify(aiClassifyExecutor, never()).execute(any(), any(), any());
  }

  private Long tempLevel(long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.select(DATASET.SECURITY_LEVEL_ID)
                .from(DATASET)
                .where(DATASET.TABLE_NAME.like("ptmp\\_" + pipelineId + "\\_%"))
                .fetchOne(DATASET.SECURITY_LEVEL_ID));
  }

  private String stepError(long executionId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT error_message FROM pipeline_step_execution WHERE execution_id = ?"
                        + " AND status = 'FAILED'",
                    executionId)
                .get(0, String.class));
  }

  /** PipelineSqlAccessTest#waitForEnd 와 같은 폴링(최대 60초). */
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
