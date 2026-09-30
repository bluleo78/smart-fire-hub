package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
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
            "TABLE", "SOURCE",
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
    assertThat(response.error()).containsIgnoringCase("multiple");
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
    assertThat(response.rows()).extracting(r -> ((Number) r.get("g")).intValue()).containsExactly(6, 7, 8);
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
    SchemaInfoResponse schemaInfo = executionService.getSchemaInfo();

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
                    "TABLE", "SOURCE",
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
                    "TABLE", "SOURCE",
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
    SchemaInfoResponse all = executionService.getSchemaInfo();
    SchemaInfoResponse nullFiltered = executionService.getSchemaInfo(null);
    assertThat(nullFiltered.tables())
        .extracting(SchemaInfoResponse.TableInfo::tableName)
        .containsAll(all.tables().stream().map(SchemaInfoResponse.TableInfo::tableName).toList());
  }

  /** 빈 배열 전달 시 빈 응답 반환 — defensive 분기 검증. */
  @Test
  void getSchemaInfo_emptyList_returnsEmpty_defensive() {
    createTwoSchemaFixtures();
    SchemaInfoResponse res = executionService.getSchemaInfo(List.of());
    assertThat(res.tables()).isEmpty();
  }

  /** 단일 datasetId 전달 시 해당 테이블만 반환. */
  @Test
  void getSchemaInfo_singleDatasetId_returnsOnlyMatchingTable() {
    List<Long> ids = createTwoSchemaFixtures();
    Long firstId = ids.get(0);
    SchemaInfoResponse res = executionService.getSchemaInfo(List.of(firstId));
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
    SchemaInfoResponse res = executionService.getSchemaInfo(ids);
    assertThat(res.tables())
        .extracting(SchemaInfoResponse.TableInfo::tableName)
        .containsExactlyInAnyOrder("schema_test_a", "schema_test_b");
  }

  /** 존재하지 않는 datasetId 전달 시 빈 응답 반환. */
  @Test
  void getSchemaInfo_unknownDatasetId_returnsEmpty() {
    createTwoSchemaFixtures();
    SchemaInfoResponse res = executionService.getSchemaInfo(List.of(9_999_999L));
    assertThat(res.tables()).isEmpty();
  }
}
