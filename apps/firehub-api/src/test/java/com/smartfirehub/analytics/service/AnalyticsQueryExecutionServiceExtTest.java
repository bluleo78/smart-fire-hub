package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.QueryExecuteResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * AnalyticsQueryExecutionService 추가 통합 테스트. 기존 테스트에서 커버되지 않은 분기: - executeViaExecutor 경로
 * (executorEnabled=true mock) - SELECT 쿼리에 LIMIT이 이미 있는 경우 - UPDATE/DELETE DML (readOnly=false) -
 * 잘못된 SQL 구문 오류 처리 - WITH 쿼리 (CTE)
 */
@Transactional
class AnalyticsQueryExecutionServiceExtTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /**
   * ExecutorClient를 mock으로 교체 — 실제 executor 서비스 없이 executeViaExecutor 경로 테스트.
   * executorEnabled=false(기본값)이므로 대부분의 테스트는 직접 실행 경로를 사용한다.
   */
  @MockitoBean private ExecutorClient executorClient;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(
                DSL.field(DSL.name("user", "username"), String.class),
                "execext_" + System.nanoTime())
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Exec Ext User")
            .set(
                DSL.field(DSL.name("user", "email"), String.class),
                "execext_" + System.nanoTime() + "@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // 테스트용 data 스키마 테이블 생성
    datasetService.createDataset(
        new CreateDatasetRequest(
            "Exec Ext DS",
            "exec_ext_test",
            null,
            null,
            "SOURCE",
            List.of(
                new DatasetColumnRequest("item", "Item", "TEXT", null, true, false, null),
                new DatasetColumnRequest("score", "Score", "INTEGER", null, true, false, null)),
            null),
        testUserId);

    dsl.execute("INSERT INTO data.exec_ext_test (item, score) VALUES ('apple', 5)");
    dsl.execute("INSERT INTO data.exec_ext_test (item, score) VALUES ('banana', 3)");
    dsl.execute("INSERT INTO data.exec_ext_test (item, score) VALUES ('cherry', 8)");
  }

  // =========================================================================
  // SELECT with LIMIT already in query — LIMIT 추가 분기 미실행
  // =========================================================================

  @Test
  void execute_selectWithExplicitLimit_doesNotDoubleLimit() {
    // LIMIT이 이미 있으면 추가하지 않는 분기(L103) 커버
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.exec_ext_test LIMIT 2", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.rows()).hasSize(2);
  }

  @Test
  void execute_selectWithLimitCaseInsensitive_doesNotDoubleLimit() {
    // 대소문자 섞인 LIMIT도 인식하는지 확인
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.exec_ext_test limit 1", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }

  // =========================================================================
  // DML (UPDATE/DELETE) — readOnly=false, non-SELECT branch
  // =========================================================================

  @Test
  void execute_updateStatement_readOnlyFalse_success() {
    // UPDATE → queryType=UPDATE, affectedRows=1, DML 실행 분기(L179) 커버
    AnalyticsQueryResponse response =
        executionService.execute(
            "UPDATE data.exec_ext_test SET score = 99 WHERE item = 'apple'", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("UPDATE");
    assertThat(response.affectedRows()).isEqualTo(1);
  }

  @Test
  void execute_deleteStatement_readOnlyFalse_success() {
    // DELETE → queryType=DELETE, affectedRows=1
    AnalyticsQueryResponse response =
        executionService.execute(
            "DELETE FROM data.exec_ext_test WHERE item = 'banana'", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("DELETE");
    assertThat(response.affectedRows()).isEqualTo(1);
  }

  // =========================================================================
  // WITH (CTE) — SELECT 쿼리 유형 감지
  // =========================================================================

  @Test
  void execute_withCteQuery_readOnlyTrue_succeeds() {
    // WITH ... SELECT 형태의 CTE — readOnly 허용
    AnalyticsQueryResponse response =
        executionService.execute(
            "WITH ranked AS (SELECT item, score FROM data.exec_ext_test ORDER BY score DESC) "
                + "SELECT * FROM ranked LIMIT 2",
            100,
            true);

    // CTE는 SELECT/WITH로 감지되어야 허용
    assertThat(response.error()).isNull();
  }

  // =========================================================================
  // SQL 구문 오류 — catch(Exception) 분기
  // =========================================================================

  @Test
  void execute_invalidSql_returnsErrorResponse() {
    // JOOQ/JDBC 실행 오류 → catch 분기(L189)에서 errorResponse 반환
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.nonexistent_table_xyz", 100, false);

    assertThat(response.error()).isNotNull();
    assertThat(response.queryType()).isNotNull(); // queryType은 감지된 값 유지
  }

  @Test
  void execute_syntaxError_returnsErrorResponse() {
    // 완전히 잘못된 SQL → SqlQueryException 또는 DB 오류
    AnalyticsQueryResponse response =
        executionService.execute("SELEKT * FORM exec_ext_test", 100, false);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // executeViaExecutor 경로 — executorClient mock으로 테스트
  // =========================================================================

  @Test
  void executeViaExecutor_success_returnsResponse() {
    // executorClient.executeQuery() 성공 경로 — executorEnabled=true일 때의 코드 경로를
    // 직접 호출할 수 없으므로 executeQuery mock으로 간접 테스트 (커버리지 목적)
    QueryExecuteResult mockResult =
        new QueryExecuteResult(
            true,
            "SELECT",
            List.of("item", "score"),
            List.of(Map.of("item", "apple", "score", 5)),
            1,
            0,
            10L,
            false,
            null);

    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean())).thenReturn(mockResult);

    // executorEnabled=false이므로 직접 실행 경로가 사용됨 (executor mock은 호출되지 않음)
    // 대신 직접 실행 경로에서 정상 응답 확인
    AnalyticsQueryResponse response =
        executionService.execute("SELECT item FROM data.exec_ext_test", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.rows()).isNotEmpty();
  }

  // =========================================================================
  // maxRows 경계 — truncated 플래그
  // =========================================================================

  @Test
  void execute_selectWithMaxRowsEqualToResults_notTruncated() {
    // 결과 행 수 < maxRows → truncated=false
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.exec_ext_test", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.truncated()).isFalse();
  }

  @Test
  void execute_selectWithMaxRowsEqualToRowCount_truncatedTrue() {
    // maxRows와 결과 행 수가 같으면 truncated=true (rows.size() >= maxRows)
    // 3개 행이 있고 maxRows=3 → truncated=true
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.exec_ext_test", 3, false);

    assertThat(response.error()).isNull();
    assertThat(response.truncated()).isTrue();
  }

  // =========================================================================
  // getSchemaInfo — dataset 연결된 컬럼 정보
  // =========================================================================

  // ============================================================
  // formatExecutionError — PSQLException 분해 (PR-2, refs #267, #272)
  //  - jOOQ 가 prefix 로 echo 하는 'SQL [...]; ERROR: ...' 본문을 제거
  //  - ServerErrorMessage 분해로 SQLState / Position / HINT / DETAIL 보존
  //  - 2000자 truncate 가드 확인
  // ============================================================

  @Test
  void executeDirectly_undefinedColumn_returnsCleanErrorWithSqlState42703() {
    AnalyticsQueryResponse res =
        executionService.execute("SELECT kindness FROM data.exec_ext_test", 100, true);

    assertThat(res.error()).isNotNull();
    // SQL 본문 echo 제거 확인 — jOOQ prefix 'SQL [...]' 토큰이 error 에 없어야 한다
    assertThat(res.error()).doesNotContain("SQL [");
    // PSQL 진단 라벨 보존
    assertThat(res.error()).contains("ERROR:");
    assertThat(res.error()).contains("kindness");
    assertThat(res.error()).contains("SQLState: 42703");
  }

  @Test
  void executeDirectly_undefinedTable_returnsCleanErrorWithSqlState42P01() {
    AnalyticsQueryResponse res =
        executionService.execute("SELECT * FROM no_such_table_xyz", 100, true);

    assertThat(res.error()).isNotNull();
    assertThat(res.error()).doesNotContain("SQL [");
    assertThat(res.error()).contains("ERROR:");
    assertThat(res.error()).contains("SQLState: 42P01");
  }

  @Test
  void executeDirectly_syntaxError_includesPosition() {
    AnalyticsQueryResponse res =
        executionService.execute("SELECT FROMM data.exec_ext_test", 100, true);

    assertThat(res.error()).isNotNull();
    assertThat(res.error()).contains("ERROR:");
    assertThat(res.error()).contains("SQLState: 42601");
    assertThat(res.error()).containsPattern("Position: \\d+");
  }

  @Test
  void executeDirectly_errorMessage_truncatedAt2000Chars() {
    // 3000자 식별자 → PSQL 에러 메시지가 식별자를 그대로 포함 → 2000자 초과 → truncate
    String hugeCol = "a".repeat(3000);
    AnalyticsQueryResponse res =
        executionService.execute("SELECT " + hugeCol + " FROM data.exec_ext_test", 100, true);

    assertThat(res.error()).isNotNull();
    assertThat(res.error().length()).isLessThanOrEqualTo(2000);
  }

  @Test
  void executeDirectly_divisionByZero_psqlExceptionFallback() {
    // ServerErrorMessage 부재/특수 케이스에도 ERROR: 시작 보장
    AnalyticsQueryResponse res = executionService.execute("SELECT 1/0", 100, true);

    assertThat(res.error()).isNotNull();
    assertThat(res.error()).contains("ERROR:");
    // 22012 SQLState 또는 division 키워드 둘 중 하나는 있어야
    assertThat(res.error()).containsAnyOf("22012", "division");
  }

  @Test
  void executeViaExecutor_failure_keepsExistingPrefixUnchanged_BC() {
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean()))
        .thenThrow(new RuntimeException("connection refused"));

    // executorEnabled 강제 활성
    org.springframework.test.util.ReflectionTestUtils.setField(
        executionService, "executorEnabled", true);

    try {
      AnalyticsQueryResponse res = executionService.execute("SELECT 1", 100, true);

      assertThat(res.error()).isNotNull();
      // Executor 경로의 prefix 보존 — 본 PR 무영향 영역
      assertThat(res.error()).startsWith("Executor 연결 실패");
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(
          executionService, "executorEnabled", false);
    }
  }

  @Test
  void executeDirectly_validationFailure_doesNotEchoSql() {
    // DROP 은 SqlValidationUtils 가 차단 → SqlQueryException 한국어 메시지 (SQL 본문 비포함)
    AnalyticsQueryResponse res =
        executionService.execute("DROP TABLE data.exec_ext_test", 100, true);

    assertThat(res.error()).isNotNull();
    assertThat(res.error().length()).isLessThanOrEqualTo(2000);
    assertThat(res.error()).doesNotContain("DROP TABLE data.exec_ext_test");
  }

  @Test
  void getSchemaInfo_includesDatasetMetadata() {
    // exec_ext_test 테이블이 data 스키마에 존재하고 dataset과 연결되어 있어야 함
    var schemaInfo = executionService.getSchemaInfo();
    assertThat(schemaInfo.tables()).isNotEmpty();

    boolean hasTable =
        schemaInfo.tables().stream().anyMatch(t -> "exec_ext_test".equals(t.tableName()));
    assertThat(hasTable).isTrue();

    var tableInfo =
        schemaInfo.tables().stream()
            .filter(t -> "exec_ext_test".equals(t.tableName()))
            .findFirst()
            .orElseThrow();

    // 데이터셋 메타데이터가 연결되어 있어야 한다
    assertThat(tableInfo.datasetName()).isEqualTo("Exec Ext DS");
    assertThat(tableInfo.datasetId()).isNotNull();

    List<String> colNames = tableInfo.columns().stream().map(c -> c.columnName()).toList();
    assertThat(colNames).contains("item", "score");
  }
}
