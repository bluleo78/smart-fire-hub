package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 스텝 실패·건너뜀 때 실행 기록에 저장되는 오류 문구를 실제 파이프라인 실행으로 확인한다(WD-111).
 *
 * <p>예전 저장 문구는 러너가 감싼 내부 SQL({@code SQL [INSERT INTO data."out" …]})과 영어 원문뿐이라 사용자가 무엇을 고칠지 알 수
 * 없었다. 이제 "한국어 안내(고칠 방법) + 원인 원문(주 메시지·DETAIL)" 이어야 하고 내부 INSERT·probe 래핑·Position 은 없어야 한다. 원문 노출
 * 여부(WD-27 가림)는 조회 시점 규칙이라 여기서는 저장본(pipeline_step_execution.error_message)을 직접 읽는다.
 *
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돌고, 픽스처가 커밋돼 있어야 그쪽에서 보인다
 * (PipelineSelectJudgmentIntegrationTest 와 같은 이유). 이름에 난수를 붙여 공유 test DB 에서 충돌하지 않게 한다. 실행기는 test
 * 프로필에서 꺼져 있어 SqlScriptExecutor·SqlColumnProbe 직접 경로를 탄다(실행기 켠 경로는 PipelineAsyncRunnerTest 가 본다).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineStepErrorMessageIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;

  private String suffix;
  private String srcTable;
  private String outTable;
  private Long srcDatasetId;
  private Long outDatasetId;
  private Long pipelineId;
  private Long userId;

  @BeforeEach
  void setUpFixtures() {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    srcTable = "wd111_src_" + suffix;
    outTable = "wd111_out_" + suffix;

    userId = TenantRlsTestSupport.insertUser(dsl, "wd111_user_");
    // SQL 스텝은 저장·실행 시 사용자 자격으로 판정된다 — ACTIVE 멤버십 + USER 역할이 있어야 기본 등급 데이터셋을 본다.
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");

    // 출력은 code 가 기본 키 — 같은 code 를 다시 넣으면 23505 가 난다.
    srcDatasetId = createDataset(srcTable, "SOURCE");
    outDatasetId = createDataset(outTable, "DERIVED");
    dsl.execute("INSERT INTO " + src() + " (code, name) VALUES ('C0001', 'n1'), ('C0002', 'n2')");

    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "WD111 Pipeline " + suffix,
                    "스텝 오류 문구 통합 테스트",
                    List.of(step("first", "SELECT code, name FROM " + src(), outDatasetId, null))),
                userId)
            .id();
  }

  @AfterEach
  void tearDown() {
    // 파이프라인 실행이 감사 로그를 남기므로 user 정리(TestUsers.cleanup)는 마지막이다(FK). 단계마다 독립 실행한다.
    TenantRlsTestSupport.cleanupAll(
        () -> pipelineService.deletePipelineUnchecked(pipelineId),
        () -> datasetService.deleteDataset(outDatasetId),
        () -> datasetService.deleteDataset(srcDatasetId),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
  }

  /**
   * a) 출력 기본 키에 이미 있는 값을 APPEND 하면(23505) 「같은 키 값이 이미 있습니다(로드 전략 확인)」 안내 + PG 원문·DETAIL 이 저장되고, 러너가
   * 감싼 INSERT 문(jOOQ 의 SQL [...] 메아리)은 저장되지 않는다.
   */
  @Test
  void 중복_키_실패는_로드_전략_안내와_원문을_저장하고_내부_INSERT_문은_싣지_않는다() {
    dsl.execute(
        "INSERT INTO " + DataSchema.qualify(outTable) + " (code, name) VALUES ('C0001', 'x')");

    Map<String, StepRow> steps = runAndObserve();

    StepRow first = steps.get("first");
    assertThat(first.status()).isEqualTo("FAILED");
    assertThat(first.error())
        .startsWith(
            "SQL 실행 실패: 같은 키 값이 이미 있습니다. 출력 데이터셋의 기본 키(또는 유니크) 컬럼에 이미 있는 값이나 SQL 결과 안에서 겹치는"
                + " 값이 들어갔습니다. 로드 전략(REPLACE·APPEND·MERGE)과 기본 키 설정을 확인하세요.\n원인: ERROR: duplicate key"
                + " value violates unique constraint")
        .contains("DETAIL: Key (code)=(C0001) already exists.")
        .doesNotContain("INSERT INTO")
        .doesNotContain("SQL [")
        .doesNotContain(outTable + "\" (")
        .doesNotContain("Position")
        .doesNotContain("SQL execution failed");
  }

  /**
   * d) 없는 컬럼을 읽는 SELECT 는 결과 컬럼 분석(probe)에서 실패한다(42703) — 「SQL 컬럼 타입 분석 실패: 없는 컬럼 안내」 + PG 원문이고,
   * probe 래핑({@code AS _probe LIMIT 0})·Position 은 없다.
   */
  @Test
  void 없는_컬럼_분석_실패는_한국어_안내와_원문을_저장하고_probe_래핑은_싣지_않는다() {
    replaceSteps(step("first", "SELECT code, nope_col AS name FROM " + src(), outDatasetId, null));

    Map<String, StepRow> steps = runAndObserve();

    StepRow first = steps.get("first");
    assertThat(first.status()).isEqualTo("FAILED");
    assertThat(first.error())
        .isEqualTo(
            "SQL 컬럼 타입 분석 실패: SQL 에 없는 컬럼 이름이 있습니다. 컬럼 이름 철자와 참조한 테이블에 그 컬럼이 있는지 확인하세요.\n"
                + "원인: ERROR: column \"nope_col\" does not exist")
        .doesNotContain("_probe")
        .doesNotContain("Position");
  }

  /**
   * c) 별칭 없는 식 컬럼({@code SELECT 1} → {@code ?column?})으로 러너 TEMP 를 만들려 하면 AS 로 이름을 붙이라는 한국어 안내로
   * 실패한다 — 데이터셋 공용 검증의 영어 문구(Invalid name …)가 아니다.
   */
  @Test
  void 별칭_없는_식_컬럼은_AS_안내로_실패한다() {
    replaceSteps(step("first", "SELECT 1", null, null));

    Map<String, StepRow> steps = runAndObserve();

    StepRow first = steps.get("first");
    assertThat(first.status()).isEqualTo("FAILED");
    assertThat(first.error())
        .isEqualTo(
            "SQL 결과 컬럼 이름 '?column?' 은 쓸 수 없습니다. 계산 컬럼에는 AS 로 이름을 붙여 주세요(예: SELECT 1 AS value)."
                + " 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다.")
        .doesNotContain("Invalid name");
  }

  /**
   * b) 선행 스텝이 실패하면 뒤 스텝은 SKIPPED 이고, 저장 문구는 영어 "Dependency failed or skipped" 가 아니라 실행하지 않은
   * 이유(한국어)다.
   */
  @Test
  void 선행_스텝_실패로_건너뛴_스텝은_한국어_이유를_저장한다() {
    replaceSteps(
        step("first", "SELECT code, nope_col AS name FROM " + src(), outDatasetId, null),
        step("second", "SELECT code, name FROM " + src(), null, List.of("first")));

    Map<String, StepRow> steps = runAndObserve();

    assertThat(steps.get("first").status()).isEqualTo("FAILED");
    StepRow second = steps.get("second");
    assertThat(second.status()).isEqualTo("SKIPPED");
    assertThat(second.error())
        .isEqualTo("선행 스텝이 실패하거나 건너뛰어져 이 스텝은 실행하지 않았습니다.")
        .doesNotContain("Dependency failed");
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  private String src() {
    return DataSchema.qualify(srcTable);
  }

  /** code(PK)·name TEXT 두 컬럼짜리 데이터셋. createDataset 은 스스로 트랜잭션을 연다 — inTenantFixture 안에 넣지 않는다. */
  private Long createDataset(String table, String type) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false));
    return datasetService
        .createDataset(
            new CreateDatasetRequest(
                "WD111 " + table, table, null, null, "TABLE", type, columns, null),
            userId)
        .id();
  }

  private static PipelineStepRequest step(
      String name, String sql, Long outputId, List<String> dependsOn) {
    return new PipelineStepRequest(name, "오류 문구", "SQL", sql, outputId, null, dependsOn, "APPEND");
  }

  private void replaceSteps(PipelineStepRequest... steps) {
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest("WD111 Pipeline " + suffix, "스텝 오류 문구", null, List.of(steps)),
        userId);
  }

  /** 스텝 이름별 실행 상태·저장 오류 문구. */
  private record StepRow(String status, String error) {}

  /** 파이프라인을 실행하고 종료 상태까지 기다린 뒤 스텝별 저장본을 돌려준다. */
  private Map<String, StepRow> runAndObserve() {
    Long executionId = executionService.executePipeline(pipelineId, userId);
    long deadline = System.currentTimeMillis() + 60_000;
    ExecutionDetailResponse detail = null;
    while (System.currentTimeMillis() < deadline) {
      detail = pipelineService.getExecutionById(pipelineId, executionId);
      if ("COMPLETED".equals(detail.status()) || "FAILED".equals(detail.status())) {
        break;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertThat(detail).isNotNull();
    // 조회 API 는 조회자 판정(WD-27)으로 가림 문구를 돌려줄 수 있어 저장본을 직접 읽는다.
    List<Record> rows =
        inTenantFixture(
            () ->
                dsl.fetch(
                    "SELECT ps.name, pse.status, pse.error_message FROM pipeline_step_execution pse"
                        + " JOIN pipeline_step ps ON ps.id = pse.step_id WHERE pse.execution_id = ?",
                    executionId));
    Map<String, StepRow> byName = new HashMap<>();
    for (Record r : rows) {
      byName.put(
          r.get(0, String.class), new StepRow(r.get(1, String.class), r.get(2, String.class)));
    }
    return byName;
  }
}
