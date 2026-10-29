package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL 스텝의 "조회인가" 판정(SqlValidator#validate 가 돌려주는 AST 문장 종류)을 실제 파이프라인 실행으로 끝에서 끝까지 확인한다
 * (WD-93·WD-94).
 *
 * <p><b>왜 실행까지 관통하나.</b> 판정이 틀리면 결과가 "실패"가 아니라 <b>조용한 손실</b>로 나타난다 — 진짜 조회를 DML 로 보면 러너가 결과를 적재하지
 * 않고 그대로 실행해 버리는데, REPLACE 출력은 비우기(DELETE)가 먼저 들어가 출력이 빈 채로 COMPLETED 가 된다. 판정 단위 테스트로는 "그래서 출력에
 * 무엇이 남는가"가 보이지 않으므로, 출력에 조회가 낼 수 없는 표식 행(K*)을 심어 두고 실행 뒤 출력 내용을 그대로 비교한다.
 *
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돌고, 픽스처가 커밋돼 있어야 그쪽에서 보인다
 * (PipelineIncrementalIntegrationTest 와 같은 이유). 이름에 난수를 붙여 공유 test DB 에서 충돌하지 않게 한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineSelectJudgmentIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;

  private static final String STEP_NAME = "judge-step";

  /** 원천 행. 원천 code 는 C0001~C0003, 출력 표식은 K1~K2 라 서로 겹치지 않는다. */
  private static final String EXPECTED_LOADED = "C0001:n1,C0002:n2,C0003:n3";

  /** 실행 전 출력에 심는 표식 행 — 어떤 조회도 이 행을 내지 않는다. */
  private static final String SENTINEL = "K1:keep,K2:keep";

  /** 케이스 SQL 템플릿에서 원천 테이블 자리. */
  private static final String SRC = "$SRC";

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
    srcTable = "wd93_src_" + suffix;
    outTable = "wd93_out_" + suffix;

    userId = TenantRlsTestSupport.insertUser(dsl, "wd93_user_");
    // 파이프라인 SQL 스텝은 저장·실행 시 사용자 자격으로 판정된다 — ACTIVE 멤버십 + USER 역할이 있어야 기본 등급 데이터셋을 본다.
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");

    // code 를 PK 로 둔다 — MERGE 전략 케이스가 출력 PK 를 요구한다.
    srcDatasetId = createDataset(srcTable, "SOURCE", "code", "name");
    outDatasetId = createDataset(outTable, "DERIVED", "code", "name");
    dsl.execute(
        "INSERT INTO "
            + src()
            + " (code, name) SELECT 'C' || lpad(i::text, 4, '0'), 'n' || i"
            + " FROM generate_series(1, 3) AS i");
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(outTable)
            + " (code, name) VALUES ('K1', 'keep'), ('K2', 'keep')");

    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "WD93 Pipeline " + suffix,
                    "SELECT 판정 통합 테스트",
                    List.of(step("SELECT code, name FROM " + src(), "APPEND", outDatasetId))),
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

  // ------------------------------------------------------------------ //
  // 진짜 조회 — 판정이 틀리면 REPLACE 출력이 빈 채로 COMPLETED(조용한 손실)
  // ------------------------------------------------------------------ //

  /**
   * 진짜 조회는 전략과 무관하게 결과가 출력에 적재돼야 한다. REPLACE 는 표식이 지워지고 원천 3행만, APPEND·MERGE 는 표식 + 원천 3행이 남는다.
   * 상태·출력·오류를 한 문자열로 비교해, 실패하면 실제로 무슨 일이 났는지가 메시지에 그대로 보이게 한다. MERGE 케이스는 저장 시점 MERGE 허용 판정(조회
   * 스텝만)도 함께 검증한다.
   */
  @ParameterizedTest(name = "[{index}] {0} / {1}")
  @MethodSource("genuineSelectCases")
  void 진짜_SELECT_는_출력에_적재된다(String strategy, String label, String template) {
    replaceStep(template.replace(SRC, src()), strategy);

    Observed observed = runAndObserve();

    String expectedOut =
        "REPLACE".equals(strategy) ? EXPECTED_LOADED : SENTINEL + "," + EXPECTED_LOADED;
    assertThat(observed.summary())
        .as("%s / %s 의 실행 결과", strategy, label)
        .isEqualTo("COMPLETED | out=" + sortedOf(expectedOut) + " | error=null");
  }

  static Stream<Arguments> genuineSelectCases() {
    List<String[]> base =
        List.of(
            // CTE 이름에 DML 단어 — 예전 최상위 startsWith 스캔이 본문 키워드로 오인했다.
            c(
                "CTE 이름 updated",
                "WITH updated AS (SELECT code, name FROM $SRC) SELECT * FROM updated"),
            c(
                "CTE 이름 deleted_rows",
                "WITH deleted_rows AS (SELECT code, name FROM $SRC) SELECT code, name FROM deleted_rows"),
            c(
                "CTE 이름 inserted_at",
                "with inserted_at as (select code, name from $SRC) select code, name from inserted_at"),
            c(
                "따옴표 CTE 이름 \"update_at\"",
                "WITH \"update_at\" AS (SELECT code, name FROM $SRC) SELECT code, name FROM \"update_at\""),
            // update 는 PostgreSQL 비예약어라 CTE 이름이 될 수 있다.
            c(
                "CTE 이름 update(비예약어)",
                "WITH update AS (SELECT code, name FROM $SRC) SELECT code, name FROM update"),
            // 최상위 주석 속 단어는 키워드가 아니다.
            c(
                "CTE 뒤 주석 속 delete",
                "WITH x AS (SELECT code, name FROM $SRC)\n-- delete old rows later\nSELECT code, name FROM x"),
            // 맨 앞 주석 — 예전 stripLeading 은 주석을 걷지 않았다.
            c("맨 앞 줄 주석", "-- 설명\nSELECT code, name FROM $SRC"),
            c("맨 앞 블록 주석", "/* note */ SELECT code, name FROM $SRC"),
            c(
                "맨 앞 주석 + WITH",
                "/* it's */\n-- x\nWITH a AS (SELECT code, name FROM $SRC) SELECT * FROM a"),
            // 예전 어휘 판정이 DML 로 보던 조회 형태(WD-94) — 러너 래퍼(probe 서브쿼리·INSERT … <조회>·MERGE 의 FROM
            // (<조회>))가
            // 그대로 감싼다.
            c("괄호로 감싼 SELECT", "(SELECT code, name FROM $SRC)"),
            c(
                "괄호 UNION",
                "(SELECT code, name FROM $SRC WHERE code = 'C0001')"
                    + " UNION (SELECT code, name FROM $SRC WHERE code <> 'C0001')"),
            c(
                "UNION ALL",
                "SELECT code, name FROM $SRC WHERE code = 'C0001'"
                    + " UNION ALL SELECT code, name FROM $SRC WHERE code <> 'C0001'"),
            // 회귀 가드: 예전에도 맞던 형태(앞 개행·공백, 소문자, 키워드 뒤 구두점).
            c("SELECT*FROM 소문자", "\n  select*from (select code, name from $SRC) AS s"));
    return Stream.of("REPLACE", "APPEND", "MERGE")
        .flatMap(strategy -> base.stream().map(b -> Arguments.of(strategy, b[0], b[1])));
  }

  /** 케이스 한 줄 — 라벨과 SQL 템플릿({@link #SRC} 자리에 원천 테이블). */
  private static String[] c(String label, String template) {
    return new String[] {label, template};
  }

  /**
   * {@code WITH a AS (…) (SELECT … FROM a)}(괄호 본문)는 조회로 판정되고 MERGE 로는 적재된다. REPLACE·APPEND 는 실행기를 끈
   * 경로(이 테스트 프로필)에서 SqlScriptExecutor 가 감싼 {@code INSERT INTO out (…) WITH a AS (…) (SELECT … FROM
   * a)} 를 다시 검증하는데, 검증기가 이 모양에서 CTE 이름 {@code a} 를 실제 테이블로 보고 "스키마가 없습니다"로 거부한다(검증기 CTE 범위 오탐 — 후속
   * 과제). 어느 쪽이든 <b>출력을 조용히 비우지 않는다</b>는 것만 고정한다: 적재됐거나, 실패했고 출력이 그대로다.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"REPLACE", "APPEND", "MERGE"})
  void WITH_뒤_괄호_본문은_조용히_비우지_않는다(String strategy) {
    replaceStep(
        "WITH a AS (SELECT code, name FROM " + src() + ") (SELECT code, name FROM a)", strategy);

    Observed observed = runAndObserve();

    String loaded =
        sortedOf("REPLACE".equals(strategy) ? EXPECTED_LOADED : SENTINEL + "," + EXPECTED_LOADED);
    if ("MERGE".equals(strategy)) {
      assertThat(observed.summary()).isEqualTo("COMPLETED | out=" + loaded + " | error=null");
    } else {
      assertThat(observed.summary())
          .satisfiesAnyOf(
              v -> assertThat(v).isEqualTo("COMPLETED | out=" + loaded + " | error=null"),
              v -> assertThat(v).startsWith("FAILED | out=" + sortedOf(SENTINEL) + " | error="));
    }
  }

  // ------------------------------------------------------------------ //
  // VALUES·TABLE — 조회로 적재되지만 결과 컬럼이 출력과 맞아야 한다(WD-94)
  // ------------------------------------------------------------------ //

  /**
   * {@code VALUES} 는 결과 컬럼 이름이 column1·column2… 로 정해진다. 출력 데이터셋 컬럼이 그 이름이면 전략과 무관하게 적재된다 — 예전에는 DML
   * 로 보고 그대로 실행해 결과를 버렸다(REPLACE 는 출력이 빈 채 완료).
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"REPLACE", "APPEND", "MERGE"})
  void VALUES_는_컬럼명이_맞는_출력에_적재된다(String strategy) {
    String valsTable = "wd93_vals_" + suffix;
    Long valsId = createDataset(valsTable, "DERIVED", "column1", "column2");
    try {
      dsl.execute(
          "INSERT INTO "
              + DataSchema.qualify(valsTable)
              + " (column1, column2) VALUES ('K1', 'keep')");
      replaceStep(step("VALUES ('V1', 'a'), ('V2', 'b')", strategy, valsId));

      Observed observed = runAndObserve();

      String expected = "REPLACE".equals(strategy) ? "V1:a,V2:b" : "K1:keep,V1:a,V2:b";
      assertThat(observed.status() + " | " + rowsOf(valsTable, "column1", "column2"))
          .as(observed.summary())
          .isEqualTo("COMPLETED | " + expected);
    } finally {
      // 파이프라인이 이 데이터셋을 출력으로 참조하므로 원래 출력으로 되돌린 뒤 지운다.
      replaceStep("SELECT code, name FROM " + src(), "APPEND");
      datasetService.deleteDataset(valsId);
    }
  }

  /**
   * 결과 컬럼이 출력 컬럼과 하나도 맞지 않으면(이름 없는 {@code VALUES} → column1…) 러너가 실행 전에 명확한 오류로 실패시키고, REPLACE 비우기도
   * 하지 않는다 — 조용히 비우는 대신 실패한다.
   */
  @Test
  void 컬럼이_맞지_않는_VALUES_는_명확한_오류로_실패하고_출력이_보존된다() {
    replaceStep("VALUES ('V1', 'a')", "REPLACE");

    Observed observed = runAndObserve();

    assertThat(observed.status()).isEqualTo("FAILED");
    assertThat(observed.error()).contains("출력 데이터셋의 컬럼과 일치하지 않습니다");
    assertThat(observed.out()).isEqualTo(sortedOf(SENTINEL));
  }

  /**
   * {@code TABLE t} 는 러너가 감쌀 수 없다 — 컬럼 probe({@code SELECT * FROM (TABLE t) …})·{@code INSERT …
   * TABLE t}·MERGE 의 {@code FROM (TABLE t)} 를 JSqlParser 가 해석하지 못해 실행 때마다 실패했다. 그래서 저장 시점에 고칠 방법과
   * 함께 거부한다. 거부는 전략과 무관한 검증 단계라 전략 하나로 본다. 예전에는 DML 로 보고 그대로 실행해 결과를 버렸다(REPLACE 는 출력이 빈 채 완료).
   */
  @Test
  void TABLE_문은_저장에서_명확한_안내와_함께_거부된다() {
    assertThatThrownBy(() -> replaceStep("TABLE " + src(), "REPLACE"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("TABLE 문은 실행할 수 없습니다");
  }

  // ------------------------------------------------------------------ //
  // 반대 방향 — DML·데이터 수정 CTE 는 적재 경로를 타면 안 된다
  // ------------------------------------------------------------------ //

  /**
   * {@code WITH selected AS (SELECT …) DELETE …} 는 본문이 DELETE 인 DML 이다. 예전엔 CTE 이름 selected 를
   * SELECT 로 오인해 적재 래핑을 시도하다 실패했다. 이제 DML 로 실행돼 원천에서 한 행이 지워지고, REPLACE 출력은 비워진다(사용자 DML 스텝 +
   * REPLACE 의 기존 계약).
   */
  @Test
  void CTE_이름이_selected_인_DELETE_는_DML_로_실행된다() {
    replaceStep(
        "WITH selected AS (SELECT code FROM "
            + src()
            + " WHERE code = 'C0001') DELETE FROM "
            + src()
            + " WHERE code IN (SELECT code FROM selected)",
        "REPLACE");

    Observed observed = runAndObserve();

    assertThat(observed.summary() + " | src=" + rowsOf(srcTable, "code", "name"))
        .isEqualTo("COMPLETED | out= | error=null | src=C0002:n2,C0003:n3");
  }

  /**
   * 데이터 수정 CTE + SELECT 본문({@code WITH d AS (DELETE … RETURNING …) SELECT …})은 SqlValidator 가 저장
   * 시점(그리고 실행 직전 재검증)에 거부한다. 판정과 검증이 같은 메서드라 판정만 통과하는 길이 없다. 이 문장이 DML 로 실행되면 원천을 지우고 REPLACE 출력까지
   * 비운 채 COMPLETED 가 되므로 거부를 고정한다.
   */
  @Test
  void 데이터_수정_CTE_뒤_SELECT_는_저장에서_거부된다() {
    assertThatThrownBy(
            () ->
                replaceStep(
                    "WITH d AS (DELETE FROM "
                        + src()
                        + " WHERE code = 'C0001' RETURNING code, name) SELECT code, name FROM d",
                    "REPLACE"))
        .isInstanceOf(UnsafeSqlException.class);
    assertThat(rowsOf(srcTable, "code", "name")).isEqualTo(sortedOf(EXPECTED_LOADED));
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  private String src() {
    return DataSchema.qualify(srcTable);
  }

  /** 두 TEXT 컬럼(첫 컬럼이 PK)짜리 데이터셋을 만든다. createDataset 은 스스로 트랜잭션을 연다 — inTenantFixture 안에 넣지 않는다. */
  private Long createDataset(String table, String type, String keyColumn, String valueColumn) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest(keyColumn, keyColumn, "TEXT", null, false, false, null, true),
            new DatasetColumnRequest(
                valueColumn, valueColumn, "TEXT", null, true, false, null, false));
    return datasetService
        .createDataset(
            new CreateDatasetRequest(
                "WD93 " + table, table, null, null, "TABLE", type, columns, null),
            userId)
        .id();
  }

  private PipelineStepRequest step(String sql, String strategy, Long outputId) {
    return new PipelineStepRequest(STEP_NAME, "판정", "SQL", sql, outputId, null, null, strategy);
  }

  /** 기본 출력(outTable)으로 스텝을 바꾼다. */
  private void replaceStep(String sql, String strategy) {
    replaceStep(step(sql, strategy, outDatasetId));
  }

  private void replaceStep(PipelineStepRequest step) {
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest("WD93 Pipeline " + suffix, "SELECT 판정", null, List.of(step)),
        userId);
  }

  /** 실행의 최종 상태·출력(outTable) 내용·스텝 오류. */
  private record Observed(String status, String out, String error) {
    String summary() {
      return status + " | out=" + out + " | error=" + error;
    }
  }

  /** 파이프라인을 실행하고 종료 상태까지 기다린 뒤 관찰 결과를 돌려준다. */
  private Observed runAndObserve() {
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
    // 오류는 실행 이력 원문을 읽는다 — 조회 API 는 조회자 판정(WD-27)으로 가림 문구를 돌려줘 실제 원인이 보이지 않는다.
    String error =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT max(error_message) FROM pipeline_step_execution WHERE execution_id = ?",
                        executionId)
                    .get(0, String.class));
    return new Observed(detail.status(), rowsOf(outTable, "code", "name"), error);
  }

  /** 테이블 행을 키 순으로 "키:값,…" 한 줄로. */
  private String rowsOf(String table, String keyColumn, String valueColumn) {
    return String.join(
        ",",
        dsl.fetch(
                "SELECT "
                    + keyColumn
                    + " || ':' || "
                    + valueColumn
                    + " FROM "
                    + DataSchema.qualify(table)
                    + " ORDER BY "
                    + keyColumn)
            .getValues(0, String.class));
  }

  private static String sortedOf(String rows) {
    return String.join(",", Stream.of(rows.split(",")).sorted().toList());
  }
}
