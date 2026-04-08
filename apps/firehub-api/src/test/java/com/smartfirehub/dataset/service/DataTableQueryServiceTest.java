package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DataTableQueryService 통합 테스트.
 *
 * <p>사용자 SQL 실행 보안 로직을 중점적으로 검증한다:
 * - SAVEPOINT 트랜잭션 격리: SQL 오류 후에도 외부 트랜잭션이 계속 동작해야 함
 * - search_path 제한: data 스키마만 접근 가능, public 스키마 테이블 접근 차단
 * - SELECT 자동 LIMIT 주입: LIMIT 없는 SELECT에 maxRows 적용
 * - 기존 LIMIT 보존: 사용자가 지정한 LIMIT보다 maxRows가 크더라도 사용자 LIMIT 유지
 * - DDL 차단: SqlValidationUtils를 통한 키워드 필터링
 * - 멀티스테이트먼트 차단: 세미콜론으로 구분된 복수 쿼리 거부
 * - 시스템 컬럼 필터링: id, import_id, created_at 컬럼 응답에서 제외
 * - DML 실행: INSERT, UPDATE, DELETE affectedRows 반환
 * - CTE(WITH) 쿼리 지원
 * - SQL 문법 오류: error 필드에 메시지 기록, 트랜잭션 계속 진행
 */
@Transactional
class DataTableQueryServiceTest extends IntegrationTestBase {

  @Autowired private DataTableQueryService dataTableQueryService;
  @Autowired private DatasetService datasetService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DSLContext dsl;

  /** 테스트용 사용자 ID */
  private Long testUserId;
  /** 테스트용 데이터 테이블명 */
  private String testTableName;
  /** 테스트용 데이터셋 ID */
  private Long testDatasetId;

  // =========================================================================
  // Setup
  // =========================================================================

  @BeforeEach
  void setUp() {
    // 테스트 사용자 생성
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "query_service_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Query Service User")
            .set(USER.EMAIL, "query_service@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // 테스트용 데이터셋 + 데이터 준비
    testTableName = "query_test_table";
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Query Test DS", testTableName, null, null, "SOURCE", columns, null),
            testUserId);
    testDatasetId = dataset.id();

    // 샘플 데이터 3건 삽입
    for (int i = 1; i <= 3; i++) {
      datasetDataService.addRow(
          testDatasetId,
          new RowDataRequest(
              java.util.Map.of("name", "Row" + i, "value", i * 10)));
    }
  }

  // =========================================================================
  // SELECT 정상 케이스
  // =========================================================================

  /** 정상: SELECT 쿼리는 rows와 columns를 반환하고 error가 null */
  @Test
  void executeQuery_select_returnsRowsAndColumns() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName, 100);

    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(3);
    assertThat(response.executionTimeMs()).isGreaterThanOrEqualTo(0);
  }

  /** 정상: SELECT 응답의 columns 목록에 시스템 컬럼(id, import_id, created_at)이 포함되지 않는다 */
  @Test
  void executeQuery_select_filtersSystemColumns() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName, 100);

    assertThat(response.columns()).doesNotContain("id", "import_id", "created_at");
    assertThat(response.columns()).contains("name", "value");
  }

  /** 정상: LIMIT 없는 SELECT에 maxRows가 자동으로 주입된다 */
  @Test
  void executeQuery_select_withoutLimit_injectsMaxRows() {
    // 3건 데이터가 있고 maxRows=2로 요청 → 2건만 반환
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName, 2);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(2);
  }

  /** 정상: 사용자가 명시한 LIMIT은 maxRows보다 우선한다 (LIMIT 재주입 없음) */
  @Test
  void executeQuery_select_withExplicitLimit_preservesUserLimit() {
    // 3건 데이터, 사용자가 LIMIT 1 지정, maxRows=100이지만 사용자 LIMIT 유지
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName + " LIMIT 1", 100);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
  }

  /** 정상: LIMIT이 대소문자 혼용이어도 사용자 LIMIT 보존 */
  @Test
  void executeQuery_select_withMixedCaseLimit_preservesUserLimit() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName + " limit 2", 100);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(2);
  }

  /** 정상: WHERE 조건으로 필터링하면 조건에 맞는 행만 반환 */
  @Test
  void executeQuery_select_withWhereClause_returnsFilteredRows() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName + " WHERE name = 'Row1'", 100);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
    assertThat(response.rows().get(0).get("name")).isEqualTo("Row1");
  }

  /** 정상: 트레일링 세미콜론이 있는 SELECT도 정상 실행 */
  @Test
  void executeQuery_select_withTrailingSemicolon_success() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName + ";", 100);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(3);
  }

  /** 정상: 블록 주석이 포함된 SELECT도 정상 실행 */
  @Test
  void executeQuery_select_withBlockComment_success() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "/* 데이터 조회 */ SELECT * FROM " + testTableName, 100);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(3);
  }

  /** 정상: 라인 주석이 포함된 SELECT도 정상 실행 */
  @Test
  void executeQuery_select_withLineComment_success() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName + " -- 전체 조회\n", 100);

    assertThat(response.error()).isNull();
  }

  // =========================================================================
  // DML 정상 케이스
  // =========================================================================

  /** 정상: INSERT 쿼리는 queryType=INSERT, affectedRows=1 반환 */
  @Test
  void executeQuery_insert_returnsAffectedRows() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "INSERT INTO " + testTableName + " (name, value) VALUES ('NewRow', 999)", 100);

    assertThat(response.queryType()).isEqualTo("INSERT");
    assertThat(response.error()).isNull();
    assertThat(response.affectedRows()).isEqualTo(1);
    assertThat(response.rows()).isEmpty();
  }

  /** 정상: UPDATE 쿼리는 queryType=UPDATE, affectedRows 반환 */
  @Test
  void executeQuery_update_returnsAffectedRows() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "UPDATE " + testTableName + " SET value = 999 WHERE name = 'Row1'", 100);

    assertThat(response.queryType()).isEqualTo("UPDATE");
    assertThat(response.error()).isNull();
    assertThat(response.affectedRows()).isEqualTo(1);
  }

  /** 정상: DELETE 쿼리는 queryType=DELETE, affectedRows 반환 */
  @Test
  void executeQuery_delete_returnsAffectedRows() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "DELETE FROM " + testTableName + " WHERE name = 'Row1'", 100);

    assertThat(response.queryType()).isEqualTo("DELETE");
    assertThat(response.error()).isNull();
    assertThat(response.affectedRows()).isEqualTo(1);
  }

  // =========================================================================
  // CTE (WITH) 쿼리
  // =========================================================================

  /** 정상: WITH ... SELECT (CTE) 쿼리는 SELECT로 분류되어 실행된다 */
  @Test
  void executeQuery_withCte_select_success() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "WITH cte AS (SELECT * FROM " + testTableName + ") SELECT * FROM cte", 100);

    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(3);
  }

  // =========================================================================
  // search_path 보안 — public 스키마 접근 차단
  // =========================================================================

  /**
   * 보안: search_path가 data로 제한되므로 public 스키마의 "user" 테이블에 스키마 없이 접근하면 실패한다.
   *
   * <p>SET LOCAL search_path = 'data' 로 인해 public."user" 테이블을
   * 단순히 "user" 로만 참조하면 테이블을 찾지 못해야 한다.
   */
  @Test
  void executeQuery_select_publicSchemaWithoutPrefix_accessDenied() {
    // "user"는 public 스키마에 있으므로 search_path=data 상태에서 접근 불가
    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT * FROM \"user\"", 10);

    // error가 null이 아니어야 한다 (테이블 not found)
    assertThat(response.error()).isNotNull();
  }

  /**
   * 보안: 실행 후 search_path가 public, data로 복원되어
   * 이후 public 스키마 접근이 정상 동작해야 한다 (FINALLY 블록 검증).
   */
  @Test
  void executeQuery_afterExecution_searchPathRestored() {
    // 일단 쿼리 실행 (성공이든 실패든)
    dataTableQueryService.executeQuery("SELECT * FROM " + testTableName, 10);

    // search_path 복원 후 public 스키마의 user 테이블 접근 가능
    // DSLContext는 동일 트랜잭션을 사용하므로 복원된 search_path 확인 가능
    Long count =
        dsl.selectCount()
            .from(USER)
            .fetchOne(0, Long.class);
    assertThat(count).isGreaterThanOrEqualTo(0); // 접근 가능하면 예외 없이 실행됨
  }

  // =========================================================================
  // SAVEPOINT 트랜잭션 격리
  // =========================================================================

  /**
   * 보안/트랜잭션: SQL 문법 오류가 발생해도 SAVEPOINT 롤백으로 외부 트랜잭션이 중단되지 않는다.
   * 오류 응답 반환 후 동일 트랜잭션에서 추가 쿼리를 실행할 수 있어야 한다.
   */
  @Test
  void executeQuery_sqlError_savepointRollback_transactionContinues() {
    // 문법 오류 쿼리 실행
    SqlQueryResponse errorResponse =
        dataTableQueryService.executeQuery("SELECT * FORM invalid_syntax_table", 10);

    assertThat(errorResponse.error()).isNotNull();

    // 동일 트랜잭션에서 이후 정상 쿼리 실행 가능해야 함 (트랜잭션이 aborted 상태가 아님)
    SqlQueryResponse okResponse =
        dataTableQueryService.executeQuery("SELECT * FROM " + testTableName, 10);

    assertThat(okResponse.error()).isNull();
    assertThat(okResponse.rows()).hasSize(3);
  }

  /** 보안/트랜잭션: 존재하지 않는 테이블 조회도 SAVEPOINT로 격리되어 이후 쿼리 정상 동작 */
  @Test
  void executeQuery_tableNotFound_savepointRollback_transactionContinues() {
    SqlQueryResponse errorResponse =
        dataTableQueryService.executeQuery("SELECT * FROM non_existent_table_xyz", 10);

    assertThat(errorResponse.error()).isNotNull();

    // 이후 정상 쿼리 실행 가능
    SqlQueryResponse okResponse =
        dataTableQueryService.executeQuery(
            "SELECT * FROM " + testTableName + " LIMIT 1", 100);

    assertThat(okResponse.error()).isNull();
  }

  // =========================================================================
  // DDL / 멀티스테이트먼트 차단 (SqlQueryException)
  // =========================================================================

  /** 보안: DDL(CREATE TABLE)은 SqlQueryException을 발생시켜야 한다 */
  @Test
  void executeQuery_createTable_throwsSqlQueryException() {
    assertThatThrownBy(
            () ->
                dataTableQueryService.executeQuery(
                    "CREATE TABLE data.hack_table (id BIGINT)", 100))
        .isInstanceOf(SqlQueryException.class);
  }

  /** 보안: DDL(DROP TABLE)은 SqlQueryException을 발생시켜야 한다 */
  @Test
  void executeQuery_dropTable_throwsSqlQueryException() {
    assertThatThrownBy(
            () ->
                dataTableQueryService.executeQuery(
                    "DROP TABLE data." + testTableName, 100))
        .isInstanceOf(SqlQueryException.class);
  }

  /** 보안: DDL(ALTER TABLE)은 SqlQueryException을 발생시켜야 한다 */
  @Test
  void executeQuery_alterTable_throwsSqlQueryException() {
    assertThatThrownBy(
            () ->
                dataTableQueryService.executeQuery(
                    "ALTER TABLE data." + testTableName + " ADD COLUMN x TEXT", 100))
        .isInstanceOf(SqlQueryException.class);
  }

  /** 보안: TRUNCATE는 SqlQueryException을 발생시켜야 한다 */
  @Test
  void executeQuery_truncate_throwsSqlQueryException() {
    assertThatThrownBy(
            () -> dataTableQueryService.executeQuery("TRUNCATE data." + testTableName, 100))
        .isInstanceOf(SqlQueryException.class);
  }

  /** 보안: 멀티스테이트먼트(세미콜론으로 구분된 복수 쿼리)는 차단된다 */
  @Test
  void executeQuery_multiStatement_throwsSqlQueryException() {
    assertThatThrownBy(
            () ->
                dataTableQueryService.executeQuery(
                    "SELECT 1; DROP TABLE data." + testTableName, 100))
        .isInstanceOf(SqlQueryException.class)
        .hasMessageContaining("Multiple statements");
  }

  /** 보안: GRANT 문은 SqlQueryException을 발생시켜야 한다 */
  @Test
  void executeQuery_grant_throwsSqlQueryException() {
    assertThatThrownBy(
            () ->
                dataTableQueryService.executeQuery(
                    "GRANT SELECT ON data." + testTableName + " TO public", 100))
        .isInstanceOf(SqlQueryException.class);
  }

  // =========================================================================
  // SQL 오류 케이스 (error 필드 반환)
  // =========================================================================

  /** SQL 문법 오류는 SqlQueryException이 아니라 error 필드로 반환된다 */
  @Test
  void executeQuery_syntaxError_returnsErrorField() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT * FORM broken_sql", 100);

    assertThat(response.error()).isNotNull();
    assertThat(response.rows()).isEmpty();
    assertThat(response.executionTimeMs()).isGreaterThanOrEqualTo(0);
  }

  /** 존재하지 않는 컬럼 참조 오류는 error 필드로 반환된다 */
  @Test
  void executeQuery_unknownColumn_returnsErrorField() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "SELECT non_existent_col FROM " + testTableName, 100);

    assertThat(response.error()).isNotNull();
  }

  /** 타입 불일치 오류(문자열을 정수 컬럼에 INSERT)는 error 필드로 반환된다 */
  @Test
  void executeQuery_typeMismatch_returnsErrorField() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "INSERT INTO " + testTableName + " (name, value) VALUES ('x', 'not_a_number')", 100);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // queryType 분류 검증
  // =========================================================================

  /** SELECT 쿼리의 queryType은 "SELECT" */
  @Test
  void executeQuery_queryType_select() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT 1 AS num", 100);
    assertThat(response.queryType()).isEqualTo("SELECT");
  }

  /** INSERT 쿼리의 queryType은 "INSERT" */
  @Test
  void executeQuery_queryType_insert() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "INSERT INTO " + testTableName + " (name, value) VALUES ('T', 0)", 100);
    assertThat(response.queryType()).isEqualTo("INSERT");
  }

  /** UPDATE 쿼리의 queryType은 "UPDATE" */
  @Test
  void executeQuery_queryType_update() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "UPDATE " + testTableName + " SET value = 0 WHERE 1=0", 100);
    assertThat(response.queryType()).isEqualTo("UPDATE");
  }

  /** DELETE 쿼리의 queryType은 "DELETE" */
  @Test
  void executeQuery_queryType_delete() {
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "DELETE FROM " + testTableName + " WHERE 1=0", 100);
    assertThat(response.queryType()).isEqualTo("DELETE");
  }

  /** WITH ... INSERT CTE의 queryType은 "INSERT" */
  @Test
  void executeQuery_queryType_cteInsert() {
    // CTE로 감싼 INSERT — WITH절로 시작하지만 실제 DML은 INSERT
    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "WITH src AS (SELECT 'CTE' AS name, 42 AS value) "
                + "INSERT INTO " + testTableName + " (name, value) SELECT name, value FROM src",
            100);
    assertThat(response.queryType()).isEqualTo("INSERT");
  }

  // =========================================================================
  // executionTimeMs 검증
  // =========================================================================

  /** 실행 시간(executionTimeMs)은 항상 0 이상이어야 한다 */
  @Test
  void executeQuery_executionTimeMs_isNonNegative() {
    SqlQueryResponse success =
        dataTableQueryService.executeQuery("SELECT * FROM " + testTableName, 100);
    assertThat(success.executionTimeMs()).isGreaterThanOrEqualTo(0);

    SqlQueryResponse error =
        dataTableQueryService.executeQuery("SELECT * FORM broken_query", 100);
    assertThat(error.executionTimeMs()).isGreaterThanOrEqualTo(0);
  }
}
