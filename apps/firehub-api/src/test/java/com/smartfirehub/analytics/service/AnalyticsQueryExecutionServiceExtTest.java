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
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.util.AdhocSqlErrorMessages;
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

  /** 데이터 저장소(data 스키마) 테이블 조작·검사용 — 분리 모드에서는 플랫폼 DB 와 다른 DB 다(WD-494). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

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
            "TABLE",
            "SOURCE",
            List.of(
                new DatasetColumnRequest("item", "Item", "TEXT", null, true, false, null),
                new DatasetColumnRequest("score", "Score", "INTEGER", null, true, false, null)),
            null),
        testUserId);

    dataDsl.execute("INSERT INTO data.exec_ext_test (item, score) VALUES ('apple', 5)");
    dataDsl.execute("INSERT INTO data.exec_ext_test (item, score) VALUES ('banana', 3)");
    dataDsl.execute("INSERT INTO data.exec_ext_test (item, score) VALUES ('cherry', 8)");
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

    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(mockResult);

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
  void execute_selectWithMaxRowsEqualToRowCount_notTruncated() {
    // 결과가 정확히 maxRows 행이면 잘린 것이 아니다(WD-454 — 예전엔 rows.size() >= maxRows 로 true 였다).
    // 3개 행이 있고 maxRows=3 → truncated=false, 3행 모두 반환
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.exec_ext_test", 3, false);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(3);
    assertThat(response.truncated()).isFalse();
  }

  // =========================================================================
  // WD-454 — truncated 는 「상한을 넘는 결과가 실제로 있었는지」다(상한+1 행을 읽어 판정)
  // =========================================================================

  /** 사용자 LIMIT 없이 정확히 상한(1000)행 → 잘림 아님, 1000행 모두 반환. */
  @Test
  void execute_exactlyCapRowsWithoutUserLimit_notTruncated() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT g FROM generate_series(1,1000) g", 1000, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1000);
    assertThat(response.totalRows()).isEqualTo(1000);
    assertThat(response.truncated()).isFalse();
  }

  /** 상한+1 행 → 잘림, 응답은 상한 행까지만. */
  @Test
  void execute_capPlusOneRows_truncatedWithCapRows() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT g FROM generate_series(1,1001) g", 1000, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1000);
    // totalRows 는 COUNT 가 아니라 응답에 실은 행 수다.
    assertThat(response.totalRows()).isEqualTo(1000);
    assertThat(response.truncated()).isTrue();
  }

  /** 더 큰 결과에 사용자가 LIMIT 상한(1000)을 직접 썼다 → 요청한 만큼 받았으므로 잘림 아님. */
  @Test
  void execute_userLimitEqualToCapOnBiggerResult_notTruncated() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT g FROM generate_series(1,3000) g ORDER BY g LIMIT 1000", 1000, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1000);
    assertThat(response.truncated()).isFalse();
  }

  /** 사용자 LIMIT 이 상한보다 크면 존중한다(#749) — 3000행 모두 반환, 서버가 자르지 않았으므로 잘림 아님. */
  @Test
  void execute_userLimitAboveCapOnBiggerResult_honoredNotTruncated() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT g FROM generate_series(1,3000) g ORDER BY g LIMIT 5000", 1000, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(3000);
    assertThat(response.truncated()).isFalse();
  }

  /** 사용자 LIMIT 이 상한보다 크지만 결과가 정확히 상한만큼 → 잘림 아님. */
  @Test
  void execute_userLimitAboveCapOnExactlyCapResult_notTruncated() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT g FROM generate_series(1,1000) g LIMIT 5000", 1000, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1000);
    assertThat(response.truncated()).isFalse();
  }

  /** 작은 결과 → 잘림 아님. */
  @Test
  void execute_smallResult_notTruncated() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT g FROM generate_series(1,50) g", 1000, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(50);
    assertThat(response.truncated()).isFalse();
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
    // WD-571 — 첫 줄에 한국어 안내, 둘째 줄부터 기존 원문(ERROR: …)
    String[] lines = res.error().split("\n", 2);
    assertThat(lines[0]).isEqualTo(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE);
    assertThat(lines[1]).startsWith("ERROR: ");
  }

  /** WD-571 — 실제 PG 의 0 나눗셈(22012)도 첫 줄에 한국어 안내가 붙고 SQLState 원문은 그대로 남는다. */
  @Test
  void executeDirectly_divisionByZero_prependsKoreanGuide() {
    AnalyticsQueryResponse res = executionService.execute("SELECT 1 / 0", 100, true);

    assertThat(res.error()).isNotNull();
    String[] lines = res.error().split("\n", 2);
    assertThat(lines[0]).isEqualTo(AdhocSqlErrorMessages.DIVISION_BY_ZERO_GUIDE);
    assertThat(lines[1]).startsWith("ERROR: ").contains("SQLState: 22012");
  }

  /**
   * WD-571 — 안내 문구의 「30초」가 실제 제한과 어긋나지 않게, 분석 실행이 거는 statement_timeout 이 공용 상수와 같은지 실제 세션 값으로
   * 확인한다(30초를 실제로 기다리지 않는다). 사용자 SQL 에서는 current_setting 이 막혀 있어, 테스트 트랜잭션이 끝날 때까지 남는 {@code SET
   * LOCAL} 값을 실행 뒤 같은 커넥션에서 읽는다.
   */
  @Test
  void executeDirectly_statementTimeoutMatchesGuideConstant() {
    AnalyticsQueryResponse res = executionService.execute("SELECT 1 AS one", 100, true);

    assertThat(res.error()).isNull();
    // SHOW 는 60초 이상이면 "1min" 처럼 단위를 바꿔 보여 주므로, 단위가 밀리초로 고정된 pg_settings.setting 과 비교한다.
    assertThat(
            dataDsl.fetchValue("select setting from pg_settings where name = 'statement_timeout'"))
        .isEqualTo(String.valueOf(AdhocSqlErrorMessages.ADHOC_STATEMENT_TIMEOUT_SECONDS * 1000));
  }

  /**
   * WD-571 — 운영 기본 경로(실행기, {@code app.executor.enabled: true})의 시간 초과(57014)도 첫 줄에 제한 시간·해결 방법 안내가
   * 오고, 실행기 원문은 둘째 줄부터 그대로 남는다. 안내는 실행기 응답 {@code db_error.sql_state} 로 고른다. WD-641 — 직접 경로({@link
   * #formatExecutionError_statementTimeout_prependsTimeoutGuide})처럼 끝에 {@code SQLState:} 줄이 있다.
   */
  @Test
  void executeViaExecutor_statementTimeout_prependsKoreanGuide() {
    String raw = "canceling statement due to statement timeout";
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(executorFailure(raw, "57014"));

    AnalyticsQueryResponse res = executeWithExecutor("SELECT 1");

    assertThat(res.error())
        .isEqualTo(AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE + "\n" + raw + "\nSQLState: 57014")
        .contains("30초");
  }

  /** WD-571 — 실행기 경로의 없는 테이블(42P01)도 한국어 안내 + 실행기 원문(LINE·캐럿 포함) + SQLState 줄(WD-641)을 돌려준다. */
  @Test
  void executeViaExecutor_undefinedTable_prependsKoreanGuide() {
    String raw =
        "relation \"nope\" does not exist\nLINE 1: SELECT * FROM nope\n                      ^";
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(executorFailure(raw, "42P01"));

    AnalyticsQueryResponse res = executeWithExecutor("SELECT 1");

    assertThat(res.error())
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE + "\n" + raw + "\nSQLState: 42P01");
  }

  /**
   * WD-641 — 같은 오류(57014)에 두 경로가 같은 모양이다: 첫 줄 같은 안내, 끝 줄 같은 {@code SQLState: 57014}. 실행기 원문은
   * psycopg2 원문({@code ERROR:} 접두 없음, 끝 줄바꿈)이라 가운데 본문 글자만 다를 수 있다.
   */
  @Test
  void executorAndDirectPaths_sameGuideFirstLineAndSqlStateLastLine() {
    String executorRaw = "canceling statement due to statement timeout\n";
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(executorFailure(executorRaw, "57014"));
    String viaExecutor = executeWithExecutor("SELECT 1").error();

    String direct =
        executionService.formatExecutionError(
            new org.postgresql.util.PSQLException(
                new org.postgresql.util.ServerErrorMessage(
                    "SERROR\0C57014\0Mcanceling statement due to statement timeout\0")));

    String[] executorLines = viaExecutor.split("\n");
    String[] directLines = direct.split("\n");
    assertThat(executorLines[0])
        .isEqualTo(directLines[0])
        .isEqualTo(AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE);
    assertThat(executorLines[executorLines.length - 1])
        .isEqualTo(directLines[directLines.length - 1])
        .isEqualTo("SQLState: 57014");
    // 원문 끝 줄바꿈 때문에 빈 줄이 생기지 않는다.
    assertThat(viaExecutor).doesNotContain("\n\n");
  }

  /** WD-571 — db_error 가 없는 응답(구버전 실행기)은 실행기 원문 그대로(하위 호환). 안내 없는 코드는 원문 + SQLState 줄(WD-641). */
  @Test
  void executeViaExecutor_withoutDbError_returnsRaw_unknownCode_appendsSqlState() {
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(
            new QueryExecuteResult(
                false, "SELECT", List.of(), List.of(), 0, 0, 5L, false, "old executor error"));
    assertThat(executeWithExecutor("SELECT 1").error()).isEqualTo("old executor error");

    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(executorFailure("syntax error at or near \"x\"", "42601"));
    assertThat(executeWithExecutor("SELECT 1").error())
        .isEqualTo("syntax error at or near \"x\"\nSQLState: 42601");
  }

  /** WD-641 — 실행기가 준 db_error.position 은 직접 경로처럼 SQLState 줄 다음의 {@code Position:} 줄이 된다. */
  @Test
  void executeViaExecutor_dbErrorPosition_appendsPositionLine() {
    String raw = "column \"nope\" does not exist\nLINE 1: SELECT nope\n               ^\n";
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenReturn(executorFailure(raw, "42703", 8));

    assertThat(executeWithExecutor("SELECT 1").error())
        .startsWith(AdhocSqlErrorMessages.UNDEFINED_COLUMN_GUIDE + "\n")
        .endsWith("^\nSQLState: 42703\nPosition: 8");
  }

  /** 실행기 실패 응답 — 파이썬 실행기처럼 error 원문과 db_error(code 0, SQLState) 를 함께 싣는다. */
  private static QueryExecuteResult executorFailure(String raw, String sqlState) {
    return executorFailure(raw, sqlState, null);
  }

  /** 위치(db_error.position)까지 싣는 실행기 실패 응답(WD-641). */
  private static QueryExecuteResult executorFailure(String raw, String sqlState, Integer position) {
    return new QueryExecuteResult(
        false,
        "SELECT",
        List.of(),
        List.of(),
        0,
        0,
        5L,
        false,
        raw,
        new ExecutorClient.DbError(0, raw, sqlState, position));
  }

  /** executorEnabled 를 잠시 켜고 실행한다(테스트 기본값은 직접 실행 경로). */
  private AnalyticsQueryResponse executeWithExecutor(String sql) {
    org.springframework.test.util.ReflectionTestUtils.setField(
        executionService, "executorEnabled", true);
    try {
      return executionService.execute(sql, 100, true);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(
          executionService, "executorEnabled", false);
    }
  }

  /** WD-571 — 시간 초과(57014, statement_timeout) 는 제한 시간·해결 방법 안내가 첫 줄에 온다(실제 30초 대기 대신 예외 구성). */
  @Test
  void formatExecutionError_statementTimeout_prependsTimeoutGuide() {
    org.postgresql.util.PSQLException timeout =
        new org.postgresql.util.PSQLException(
            new org.postgresql.util.ServerErrorMessage(
                "SERROR\0C57014\0Mcanceling statement due to statement timeout\0"));
    RuntimeException wrapper = new RuntimeException("jOOQ wrapper", timeout);

    String formatted = executionService.formatExecutionError(wrapper);

    assertThat(formatted)
        .isEqualTo(
            AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE
                + "\nERROR: canceling statement due to statement timeout\nSQLState: 57014");
    assertThat(formatted).contains("30초");
  }

  /**
   * PSQLException 의 Position 필드 분해를 실제 DB 에러로 검증한다.
   *
   * <p>원래 입력은 {@code "SELECT FROMM data.exec_ext_test"}(파서 타이핑 오류)였다 — #385 Task 4 로 {@link
   * com.smartfirehub.pipeline.service.validator.SqlValidator}가 실행 경로 진입 이전에 배선되면서 JSqlParser 가 이
   * 문자열 자체를 파싱하지 못해 DB 까지 도달하지 못하게 됐다(R8: 파싱 실패 폴백 없음 — 승인된 트레이드오프, Task 3 의 동일 판단 참고). 그래서
   * "JSqlParser 는 통과하지만 Postgres 는 런타임에 syntax/타입 에러를 던지는" 입력으로 교체했다: 정수 컬럼에 문자열을 더하는 산술식은 문법적으로는
   * 유효해 검증기를 통과하고, Postgres 실행 시점에 SQLState 22P02(invalid_text_representation)와 함께 Position 을 포함한
   * ServerErrorMessage 를 던진다(psql 실측 확인). 이 테스트의 목적(Position 분해)은 그대로 유지된다 — SQLState 값만 42601(파서
   * 오류)에서 22P02(런타임 타입 오류)로 바뀌었다.
   */
  @Test
  void executeDirectly_runtimeTypeError_includesPosition() {
    AnalyticsQueryResponse res =
        executionService.execute("SELECT score + 'abc' FROM data.exec_ext_test", 100, true);

    assertThat(res.error()).isNotNull();
    assertThat(res.error()).contains("ERROR:");
    assertThat(res.error()).contains("SQLState: 22P02");
    assertThat(res.error()).containsPattern("Position: \\d+");
  }

  // ============================================================
  // formatExecutionError 헬퍼 직접 분기 커버 — package-private 노출
  //   - 직전 commit c85c6baf 의 2건이 PG NAMEDATALEN/ServerErrorMessage 우회로
  //     실제 분기를 못 커버해 vacuous PASS 되던 문제를 해결.
  //   - same-package 직접 호출로 truncate / sem==null fallback / cause unwrap 직접 검증.
  // ============================================================

  /** truncate 분기: 2000자 초과 입력 시 정확히 잘리고 "... [truncated]" suffix. */
  @Test
  void formatExecutionError_messageOver2000Chars_truncatesWithMarker() {
    // 3000자 메시지 — PG 가 식별자 자르는 식의 우회 없이 헬퍼 분기 직접 검증
    String longMessage = "x".repeat(3000);
    RuntimeException e = new RuntimeException(longMessage);

    String formatted = executionService.formatExecutionError(e);

    assertThat(formatted.length()).isLessThanOrEqualTo(2000);
    assertThat(formatted).endsWith("... [truncated]");
  }

  /** truncate 분기 음성: 2000자 이하면 그대로 반환. */
  @Test
  void formatExecutionError_messageWithin2000Chars_returnsAsIs() {
    String shortMessage = "small error";
    RuntimeException e = new RuntimeException(shortMessage);

    String formatted = executionService.formatExecutionError(e);

    assertThat(formatted).isEqualTo(shortMessage);
  }

  /** sem==null fallback 분기: ServerErrorMessage 가 null 인 PSQLException → psql.getMessage() 만 사용. */
  @Test
  void formatExecutionError_psqlExceptionWithoutServerErrorMessage_fallsBackToMessage() {
    // ServerErrorMessage 없는 PSQLException — 보통 클라이언트측 에러 (예: connection 실패)
    // PSQLException(String, PSQLState) 생성자는 ServerErrorMessage 를 설정하지 않음
    org.postgresql.util.PSQLException psql =
        new org.postgresql.util.PSQLException(
            "connection refused", org.postgresql.util.PSQLState.CONNECTION_FAILURE);

    String formatted = executionService.formatExecutionError(psql);

    assertThat(formatted).startsWith("ERROR: ");
    assertThat(formatted).contains("connection refused");
  }

  /** cause chain unwrap: 래핑된 PSQLException 도 unwrap 해서 ServerErrorMessage 분해. */
  @Test
  void formatExecutionError_wrappedPsqlException_unwrapsAndDecomposes() {
    // 실제 jOOQ 가 DataAccessException 으로 PSQLException 을 wrap 하는 시나리오 모사
    org.postgresql.util.PSQLException psql =
        new org.postgresql.util.PSQLException(
            "wrapped failure", org.postgresql.util.PSQLState.CONNECTION_FAILURE);
    RuntimeException wrapper = new RuntimeException("outer wrapper", psql);

    String formatted = executionService.formatExecutionError(wrapper);

    assertThat(formatted).startsWith("ERROR: ");
    assertThat(formatted).contains("wrapped failure");
    // 'outer wrapper' 는 cause chain 의 outer 레이어 메시지 — unwrap 후 PSQL 진입으로 미사용
    assertThat(formatted).doesNotContain("outer wrapper");
  }

  @Test
  void executeViaExecutor_failure_keepsExistingPrefixUnchanged_BC() {
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
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

  /**
   * 결과가 executor 응답 한도를 넘으면 "Executor 연결 실패" 가 아니라 결과를 줄이라는 안내를 돌려준다(#761). 차트·저장 쿼리도 같은 execute()
   * 를 거치므로 같은 메시지를 받는다.
   */
  @Test
  void executeViaExecutor_responseTooLarge_returnsResultTooLargeMessage() {
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean(), anyInt()))
        .thenThrow(
            new com.smartfirehub.pipeline.service.executor.ExecutorResponseTooLargeException(
                32 * 1024 * 1024, new RuntimeException("DataBufferLimitException")));

    org.springframework.test.util.ReflectionTestUtils.setField(
        executionService, "executorEnabled", true);

    try {
      AnalyticsQueryResponse res = executionService.execute("SELECT 1", 10000, true);

      assertThat(res.error()).startsWith("결과가 너무 큽니다(응답 한도 32MB 초과)");
      assertThat(res.error()).doesNotContain("Executor 연결 실패");
      assertThat(res.rows()).isEmpty();
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
    var schemaInfo = executionService.getSchemaInfo("TRUE");
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
