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
