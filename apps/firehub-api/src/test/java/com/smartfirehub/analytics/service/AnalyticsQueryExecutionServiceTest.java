package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AnalyticsQueryExecutionServiceTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /** 이 테스트는 필터 동작만 검증하므로 열람 조건은 항상 참(가시성 자체는 SqlPathsAccessTest 가 검증). */
  private static final String ALL_VISIBLE = "TRUE";

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "execuser")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Exec User")
            .set(DSL.field(DSL.name("user", "email"), String.class), "exec@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // Create a test table in the data schema for query execution tests
    datasetService.createDataset(
        new CreateDatasetRequest(
            "Exec Test DS",
            "exec_test",
            null,
            null,
            "TABLE",
            "SOURCE",
            List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
                new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null)),
            null),
        testUserId);

    dsl.execute("INSERT INTO data.exec_test (name, value) VALUES ('Alice', 10)");
    dsl.execute("INSERT INTO data.exec_test (name, value) VALUES ('Bob', 20)");
  }

  // =========================================================================
  // SELECT execution
  // =========================================================================

  @Test
  void execute_selectQuery_readOnlyTrue_returnsRows() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT * FROM data.exec_test", 100, true);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.rows()).hasSize(2);
    assertThat(response.columns()).contains("name", "value");
  }

  @Test
  void execute_selectQuery_readOnlyFalse_returnsRows() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT name FROM data.exec_test WHERE value = 10", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.rows()).hasSize(1);
    assertThat(response.rows().get(0).get("name")).isEqualTo("Alice");
  }

  // =========================================================================
  // 같은 이름 컬럼 (WD-85) — 행을 이름 키 Map 으로 담으므로 겹치면 앞 값이 덮였다
  // =========================================================================

  @Test
  void execute_duplicateColumnNames_keepsEachValueUnderUniqueName() {
    AnalyticsQueryResponse response = executionService.execute("SELECT 1 AS a, 2 AS a", 100, true);

    assertThat(response.error()).isNull();
    assertThat(response.columns()).containsExactly("a", "a_2");
    assertThat(response.rows().get(0)).containsEntry("a", 1).containsEntry("a_2", 2);
  }

  @Test
  void execute_duplicateColumnNames_skipsSuffixAlreadyTakenByUserColumn() {
    // 사용자가 이미 a_2 를 쓰면 다음 빈 번호(a_3)를 붙인다
    AnalyticsQueryResponse response =
        executionService.execute("SELECT 1 AS a, 2 AS a_2, 3 AS a", 100, true);

    assertThat(response.columns()).containsExactly("a", "a_2", "a_3");
    assertThat(response.rows().get(0))
        .containsEntry("a", 1)
        .containsEntry("a_2", 2)
        .containsEntry("a_3", 3);
  }

  @Test
  void execute_joinWithSameColumnNames_returnsBothSidesValues() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT s.name, t.name FROM data.exec_test s JOIN data.exec_test t"
                + " ON t.value = s.value + 10",
            100,
            true);

    assertThat(response.columns()).containsExactly("name", "name_2");
    assertThat(response.rows()).hasSize(1);
    assertThat(response.rows().get(0))
        .containsEntry("name", "Alice")
        .containsEntry("name_2", "Bob");
  }

  @Test
  void execute_duplicateColumnNamesWithGeometry_wrapsWithUniqueNames() {
    // GEOMETRY 가 있으면 ST_AsGeoJSON 래핑 CTE 로 다시 실행한다 — 이름이 겹치면 바깥 SELECT 가 모호해 실패했다
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT 1 AS id, 2 AS id, ST_SetSRID(ST_MakePoint(127, 37), 4326) AS geom", 100, true);

    assertThat(response.error()).isNull();
    assertThat(response.columns()).containsExactly("id", "id_2", "geom");
    assertThat(response.rows().get(0)).containsEntry("id", 1).containsEntry("id_2", 2);
    assertThat(String.valueOf(response.rows().get(0).get("geom"))).contains("\"Point\"");
  }

  // =========================================================================
  // DML execution — readOnly flag enforcement
  // =========================================================================

  @Test
  void execute_insertStatement_readOnlyTrue_returnsError() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "INSERT INTO data.exec_test (name, value) VALUES ('Charlie', 30)", 100, true);

    assertThat(response.error()).isNotNull();
    assertThat(response.error()).contains("SELECT");
  }

  @Test
  void execute_insertStatement_readOnlyFalse_success() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "INSERT INTO data.exec_test (name, value) VALUES ('Charlie', 30)", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("INSERT");
    assertThat(response.affectedRows()).isEqualTo(1);
  }

  @Test
  void execute_updateStatement_readOnlyTrue_returnsError() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "UPDATE data.exec_test SET value = 99 WHERE name = 'Alice'", 100, true);

    assertThat(response.error()).isNotNull();
    assertThat(response.error()).contains("SELECT");
  }

  @Test
  void execute_deleteStatement_readOnlyTrue_returnsError() {
    AnalyticsQueryResponse response =
        executionService.execute("DELETE FROM data.exec_test WHERE name = 'Bob'", 100, true);

    assertThat(response.error()).isNotNull();
    assertThat(response.error()).contains("SELECT");
  }

  // =========================================================================
  // WD-91: 첫 키워드 바로 뒤 구두점 — PostgreSQL 어휘 규칙대로 첫 키워드를 뽑는다
  // =========================================================================

  /**
   * {@code SELECT*FROM}·{@code select(a)} 는 PostgreSQL 이 받는 SELECT 다 — 실행해 행을 돌려준다(예전엔 첫 단어 검사에서
   * 거부).
   */
  @Test
  void execute_punctuationRightAfterSelect_readOnlyTrue_returnsRows() {
    AnalyticsQueryResponse star = executionService.execute("SELECT*FROM data.exec_test", 100, true);
    assertThat(star.error()).isNull();
    assertThat(star.queryType()).isEqualTo("SELECT");
    assertThat(star.rows()).hasSize(2);

    AnalyticsQueryResponse paren =
        executionService.execute("select(name) from data.exec_test where value = 10", 100, true);
    assertThat(paren.error()).isNull();
    assertThat(paren.queryType()).isEqualTo("SELECT");
    assertThat(paren.rows()).hasSize(1);
  }

  /**
   * 같은 규칙으로 DML 은 DML 로 분류돼 읽기 전용이 계속 거부한다 — 구두점이 붙은 DML 이 SELECT 로 새면 안 된다. CTE 안 DML 도 {@code
   * WITH"d"AS(...)} 처럼 붙여 써서 첫 단어 검사를 지나도 읽기 전용 검사가 막고, 실제로 지워지지 않는다.
   */
  @Test
  void execute_punctuationRightAfterDmlKeyword_readOnlyTrue_isRejectedAndNothingChanges() {
    List<String> dml =
        List.of(
            "DELETE*FROM data.exec_test",
            "UPDATE\"data\".exec_test SET value = 99",
            "WITH\"d\"AS(DELETE FROM data.exec_test RETURNING *)SELECT*FROM\"d\"",
            "WITH d AS(DELETE FROM data.exec_test RETURNING *)SELECT*FROM d");
    for (String sql : dml) {
      AnalyticsQueryResponse response = executionService.execute(sql, 100, true);
      // AST 검증기도 이들을 거부하므로 "오류가 났다"만으로는 분류를 증명하지 못한다 — 읽기 전용 검사가 거부했는지 문구로 본다.
      assertThat(response.error()).as(sql).contains("SELECT 쿼리만");
      assertThat(response.rows()).as(sql).isEmpty();
    }
    assertThat(dsl.fetchOne("SELECT count(*) FROM data.exec_test").get(0, Integer.class))
        .isEqualTo(2);
    assertThat(
            dsl.fetchOne("SELECT count(*) FROM data.exec_test WHERE value = 99")
                .get(0, Integer.class))
        .isZero();
  }

  /**
   * 보안(WD-91 리뷰): CTE 이름·따옴표 식별자에 SELECT 가 들어 있어도 본문이 DML 이면 DML 이다. 예전 본문 키워드 탐지는 단어 경계 없이 {@code
   * startsWith("SELECT")} 를 봐서 {@code selected}·{@code "select"} 를 본문 SELECT 로 오인해 읽기 전용 검사를 통과시켰고,
   * 직접 경로가 DELETE·UPDATE 를 그대로 실행했다. 읽기 전용이 거부했는지 문구로 보고, 행이 그대로인지 확인한다.
   */
  @Test
  void execute_cteNameContainingSelect_withDmlBody_readOnlyTrue_isRejectedAndNothingChanges() {
    List<String> dml =
        List.of(
            "WITH selected AS (SELECT 1) DELETE FROM data.exec_test",
            "WITH \"select\" AS (SELECT 1) UPDATE data.exec_test SET value = 99",
            "WITH\"selected\"AS(SELECT 1)DELETE FROM data.exec_test",
            // 최상위 LIMIT 이 있으면 행 제한을 덧붙이지 않아 문법 오류로 우연히 막히지도 않는다 — 실제로 쓰기가 일어나던 형태.
            "WITH selected AS (SELECT 1) INSERT INTO data.exec_test (name, value)"
                + " SELECT 'Mallory', 99 LIMIT 1 RETURNING *");
    List<String> errors = new ArrayList<>();
    for (String sql : dml) {
      errors.add(executionService.execute(sql, 100, true).error());
    }
    // 행 변화를 먼저 본다 — 분류가 틀리면 어떤 쓰기가 실제로 일어났는지가 결함의 실체다.
    assertThat(dsl.fetchOne("SELECT count(*) FROM data.exec_test").get(0, Integer.class))
        .isEqualTo(2);
    assertThat(
            dsl.fetchOne("SELECT count(*) FROM data.exec_test WHERE value = 99")
                .get(0, Integer.class))
        .isZero();
    for (int k = 0; k < dml.size(); k++) {
      assertThat(errors.get(k)).as(dml.get(k)).contains("SELECT 쿼리만");
    }
  }

  /** {@code SELECT$x} 는 PostgreSQL 에서 식별자 하나다(SELECT 가 아님) — 글자만 보는 규칙이면 SELECT 로 오분류된다. */
  @Test
  void execute_selectFollowedByIdentifierChar_isRejected() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT$x FROM data.exec_test", 100, true);
    assertThat(response.error()).startsWith("SQL 문법 오류").contains("'SELECT$x'");
  }

  // #511: readOnly 검사는 MCP AI 도구 호출과 웹 UI 애드혹 쿼리 실행이 동일 플래그를 공유해 호출
  // 맥락을 구분할 수 없으므로, 오류 메시지가 "AI 도구" 특정 문구 없이 컨텍스트 중립적이어야 한다.
  @Test
  void execute_deleteStatement_readOnlyTrue_errorMessageIsCallerNeutral() {
    AnalyticsQueryResponse response =
        executionService.execute("DELETE FROM data.exec_test WHERE name = 'Bob'", 100, true);

    assertThat(response.error()).isNotNull();
    assertThat(response.error()).doesNotContain("AI 도구");
    assertThat(response.error()).doesNotContain("웹 UI를 사용하세요");
  }

  // =========================================================================
  // DDL blocking
  // =========================================================================

  @Test
  void execute_dropTableStatement_isBlocked() {
    AnalyticsQueryResponse response =
        executionService.execute("DROP TABLE data.exec_test", 100, false);

    assertThat(response.error()).isNotNull();
  }

  @Test
  void execute_alterTableStatement_isBlocked() {
    AnalyticsQueryResponse response =
        executionService.execute("ALTER TABLE data.exec_test ADD COLUMN extra TEXT", 100, false);

    assertThat(response.error()).isNotNull();
  }

  @Test
  void execute_createTableStatement_isBlocked() {
    AnalyticsQueryResponse response =
        executionService.execute("CREATE TABLE data.forbidden (id BIGINT)", 100, false);

    assertThat(response.error()).isNotNull();
  }

  // =========================================================================
  // Multi-statement blocking
  // =========================================================================

  @Test
  void execute_multipleStatementsSeparatedBySemicolon_isBlocked() {
    AnalyticsQueryResponse response = executionService.execute("SELECT 1; SELECT 2", 100, false);

    assertThat(response.error()).isNotNull();
    assertThat(response.error()).contains("SQL 문장은 한 번에 하나만 실행할 수 있습니다");
  }

  // =========================================================================
  // 행 수 제한(maxRows) — executor 를 끈 직접 실행 경로 (#749/#750)
  // =========================================================================

  /** FETCH FIRST n 은 사용자 LIMIT 과 같은 규칙(존중) — LIMIT 을 또 붙여 구문 오류가 나면 안 된다(#749). */
  @Test
  void execute_fetchFirst_isRespectedWithoutSecondLimit() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT g FROM generate_series(1,5000) g FETCH FIRST 20 ROWS ONLY", 10, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(20);
  }

  /** OFFSET … FETCH NEXT 조합도 사용자 제한이다(#749). */
  @Test
  void execute_offsetFetchNext_isRespected() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT g FROM generate_series(1,5000) g ORDER BY g OFFSET 5 ROWS FETCH NEXT 3 ROWS ONLY",
            10,
            true);

    assertThat(response.error()).isNull();
    assertThat(response.rows())
        .extracting(r -> ((Number) r.get("g")).intValue())
        .containsExactly(6, 7, 8);
  }

  /** LIMIT ALL 은 "제한 없음" — maxRows 로 치환해 보호가 빠지지 않는다(#749). */
  @Test
  void execute_limitAll_isCappedAtMaxRows() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT g FROM generate_series(1,5000) g LIMIT ALL", 10, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(10);
  }

  /** OFFSET 뒤의 LIMIT ALL 도 값만 치환한다 — OFFSET 은 보존(#749). */
  @Test
  void execute_offsetThenLimitAll_keepsOffsetAndCapsRows() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT g FROM generate_series(1,5000) g ORDER BY g OFFSET 5 LIMIT ALL", 10, true);

    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(10);
    assertThat(((Number) response.rows().get(0).get("g")).intValue()).isEqualTo(6);
  }

  /** 문자열·CTE·스칼라 서브쿼리 속 LIMIT 은 최상위 제한이 아니다 — maxRows 가 적용돼야 한다(#750). */
  @Test
  void execute_limitInsideLiteralCteOrSubquery_doesNotBypassMaxRows() {
    for (String sql :
        List.of(
            "SELECT g, 'limit 5' AS s FROM generate_series(1,5000) g",
            "WITH a AS (SELECT g FROM generate_series(1,5000) g LIMIT 4000) SELECT * FROM a",
            "SELECT g FROM generate_series(1,5000) g WHERE g > (SELECT 0 LIMIT 1)")) {
      AnalyticsQueryResponse response = executionService.execute(sql, 10, true);

      assertThat(response.error()).as(sql).isNull();
      assertThat(response.rows()).as(sql).hasSize(10);
    }
  }

  // =========================================================================
  // getSchemaInfo
  // =========================================================================

  @Test
  void getSchemaInfo_returnsDataSchemaTablesAndColumns() {
    SchemaInfoResponse schemaInfo = executionService.getSchemaInfo(ALL_VISIBLE);

    assertThat(schemaInfo.tables()).isNotEmpty();

    // exec_test table should be visible (created in setUp)
    boolean hasExecTest =
        schemaInfo.tables().stream().anyMatch(t -> "exec_test".equals(t.tableName()));
    assertThat(hasExecTest).isTrue();

    // Its columns should include name and value
    SchemaInfoResponse.TableInfo execTestInfo =
        schemaInfo.tables().stream()
            .filter(t -> "exec_test".equals(t.tableName()))
            .findFirst()
            .orElseThrow();

    List<String> columnNames =
        execTestInfo.columns().stream().map(SchemaInfoResponse.ColumnInfo::columnName).toList();
    assertThat(columnNames).contains("name", "value");
  }

  // === getSchemaInfo(List<Long>) — datasetIds 필터 ===

  /** 두 개의 스키마 픽스처 데이터셋을 생성하고 각각의 id를 반환한다. */
  private List<Long> createTwoSchemaFixtures() {
    Long ds1 =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Schema Test A",
                    "schema_test_a",
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("col_a1", "A1", "TEXT", null, true, false, null),
                        new DatasetColumnRequest(
                            "col_a2", "A2", "INTEGER", null, true, false, null)),
                    null),
                testUserId)
            .id();
    Long ds2 =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Schema Test B",
                    "schema_test_b",
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("col_b1", "B1", "TEXT", null, true, false, null)),
                    null),
                testUserId)
            .id();
    return List.of(ds1, ds2);
  }

  /** null datasetIds 전달 시 전체 반환 — BC 검증. */
  @Test
  void getSchemaInfo_nullDatasetIds_returnsAllTables_BC() {
    List<Long> ids = createTwoSchemaFixtures();
    SchemaInfoResponse all = executionService.getSchemaInfo(ALL_VISIBLE);
    SchemaInfoResponse nullFiltered = executionService.getSchemaInfo(null, ALL_VISIBLE);
    assertThat(nullFiltered.tables())
        .extracting(SchemaInfoResponse.TableInfo::tableName)
        .containsAll(all.tables().stream().map(SchemaInfoResponse.TableInfo::tableName).toList());
  }

  /** 빈 배열 전달 시 빈 응답 반환 — defensive 분기 검증. */
  @Test
  void getSchemaInfo_emptyList_returnsEmpty_defensive() {
    createTwoSchemaFixtures();
    SchemaInfoResponse res = executionService.getSchemaInfo(List.of(), ALL_VISIBLE);
    assertThat(res.tables()).isEmpty();
  }

  /** 단일 datasetId 전달 시 해당 테이블만 반환. */
  @Test
  void getSchemaInfo_singleDatasetId_returnsOnlyMatchingTable() {
    List<Long> ids = createTwoSchemaFixtures();
    Long firstId = ids.get(0);
    SchemaInfoResponse res = executionService.getSchemaInfo(List.of(firstId), ALL_VISIBLE);
    assertThat(res.tables())
        .extracting(SchemaInfoResponse.TableInfo::datasetId)
        .containsExactly(firstId);
    assertThat(res.tables())
        .extracting(SchemaInfoResponse.TableInfo::tableName)
        .containsExactly("schema_test_a");
  }

  /** 여러 datasetId 전달 시 해당 테이블 모두 반환. */
  @Test
  void getSchemaInfo_multipleDatasetIds_returnsAllMatching() {
    List<Long> ids = createTwoSchemaFixtures();
    SchemaInfoResponse res = executionService.getSchemaInfo(ids, ALL_VISIBLE);
    assertThat(res.tables())
        .extracting(SchemaInfoResponse.TableInfo::tableName)
        .containsExactlyInAnyOrder("schema_test_a", "schema_test_b");
  }

  /** 존재하지 않는 datasetId 전달 시 빈 응답 반환. */
  @Test
  void getSchemaInfo_unknownDatasetId_returnsEmpty() {
    createTwoSchemaFixtures();
    SchemaInfoResponse res = executionService.getSchemaInfo(List.of(9_999_999L), ALL_VISIBLE);
    assertThat(res.tables()).isEmpty();
  }

  // =========================================================================
  // #753 — 위치 파라미터($n)·JDBC 자리표시자(?) 처리 (직접 실행 경로)
  // =========================================================================

  /**
   * $1 은 예전에 08P01 로 커넥션이 폐기되고 savepoint 롤백 예외가 원래 오류를 덮어 500 이 됐다(#753). 이제 실행 전에 원인 메시지로 거부하고(다른
   * 검증 실패와 같은 error 응답), 같은 트랜잭션의 다음 쿼리가 정상이어야 한다.
   */
  @Test
  void execute_positionalParameter_returnsClearError_andConnectionSurvives() {
    AnalyticsQueryResponse response =
        executionService.execute("SELECT g FROM generate_series(1,5) g WHERE g = $1", 10, true);
    assertThat(response.error()).contains("$1");

    AnalyticsQueryResponse next =
        executionService.execute("SELECT name FROM data.exec_test ORDER BY name", 10, true);
    assertThat(next.error()).isNull();
    assertThat(next.rows()).hasSize(2);
  }

  /** jsonb ?, ?|, ?& 연산자는 원문 그대로 실행돼 정상 결과를 준다(executor 경로와 같은 결과). */
  @Test
  void execute_jsonbQuestionOperators_work() {
    AnalyticsQueryResponse response =
        executionService.execute(
            "SELECT '{\"a\":1}'::jsonb ? 'a' AS has, "
                + "'{\"a\":1}'::jsonb ?| array['a','z'] AS any_, "
                + "'{\"a\":1}'::jsonb ?& array['a','z'] AS all_",
            10,
            true);
    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(1);
    assertThat(response.rows().get(0))
        .containsEntry("has", true)
        .containsEntry("any_", true)
        .containsEntry("all_", false);
  }

  /** ? 연산자 피연산자 서브쿼리로 public 스키마를 읽으려는 시도는 여전히 검증 단계에서 거부된다(#385 가드 유지). */
  @Test
  void execute_jsonbQuestionOperator_withPublicSubquery_isStillRejected() {
    AnalyticsQueryResponse r1 =
        executionService.execute(
            "SELECT '{}'::jsonb ? (SELECT username FROM public.\"user\" LIMIT 1) AS x", 10, true);
    assertThat(r1.error()).isNotNull();
    assertThat(r1.rows()).isEmpty();
    AnalyticsQueryResponse r2 =
        executionService.execute(
            "SELECT '{}'::jsonb ?& array(SELECT name FROM public.role) AS x", 10, true);
    assertThat(r2.error()).isNotNull();
    assertThat(r2.rows()).isEmpty();
    // 미한정 이름이 public 에만 있는 그림자 경로도 ? 피연산자 속에서 막힌다.
    AnalyticsQueryResponse r3 =
        executionService.execute(
            "SELECT '{}'::jsonb ? (SELECT name FROM role LIMIT 1) AS x", 10, true);
    assertThat(r3.error()).isNotNull();
    assertThat(r3.rows()).isEmpty();
  }

  /**
   * 애드혹 분석 쓰기 허용(readOnly=false) 경로도 같은 헬퍼를 쓴다 — DML … RETURNING 이 "A result was returned when none
   * was expected" 로 실패하지 않고 실제 영향 행 수를 보고해야 한다(#754).
   */
  @Test
  void execute_deleteReturning_readOnlyFalse_reportsActualAffectedRows() {
    AnalyticsQueryResponse response =
        executionService.execute("DELETE FROM data.exec_test RETURNING name", 100, false);

    assertThat(response.error()).isNull();
    assertThat(response.queryType()).isEqualTo("DELETE");
    assertThat(response.affectedRows()).isEqualTo(2);
    assertThat(dsl.fetchValue("SELECT count(*) FROM data.exec_test")).isEqualTo(0L);
  }
}
