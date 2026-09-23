package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DataTableServiceTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DataTableQueryService dataTableQueryService;

  @Autowired private DSLContext dsl;

  private final List<String> tablesToCleanup = new java.util.ArrayList<>();

  @AfterEach
  void cleanup() {
    for (String tableName : tablesToCleanup) {
      try {
        dataTableService.dropTable(tableName);
      } catch (Exception e) {
        // Ignore cleanup errors
      }
    }
    tablesToCleanup.clear();
  }

  @Test
  void createTable_withMultipleColumns_success() {
    // Given
    String tableName = "test_create_table";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, false, true, null),
            new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("score", "Score", "DECIMAL", null, true, false, null),
            new DatasetColumnRequest("active", "Active", "BOOLEAN", null, true, false, null));

    // When
    dataTableService.createTable(tableName, columns);

    // Then
    Long tableExists =
        dsl.selectCount()
            .from("information_schema.tables")
            .where("table_schema = 'data' AND table_name = '" + tableName + "'")
            .fetchOne(0, Long.class);
    assertThat(tableExists).isEqualTo(1);

    // Verify columns exist
    Long columnCount =
        dsl.selectCount()
            .from("information_schema.columns")
            .where("table_schema = 'data' AND table_name = '" + tableName + "'")
            .fetchOne(0, Long.class);
    // id, import_id, created_at + 4 custom columns = 7
    assertThat(columnCount).isGreaterThanOrEqualTo(4);

    // Verify index was created for indexed column
    Long indexCount =
        dsl.selectCount()
            .from("pg_indexes")
            .where(
                "schemaname = 'data' AND tablename = '"
                    + tableName
                    + "' AND indexname = 'idx_"
                    + tableName
                    + "_name'")
            .fetchOne(0, Long.class);
    assertThat(indexCount).isEqualTo(1);
  }

  @Test
  void insertBatch_andQueryData_success() {
    // Given
    String tableName = "test_insert_query";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    List<String> columnNames = List.of("name", "value");
    List<Map<String, Object>> rows =
        List.of(Map.of("name", "Alice", "value", 100), Map.of("name", "Bob", "value", 200));

    // When
    dataTableRowService.insertBatch(tableName, columnNames, rows);

    // Then
    long count = dataTableRowService.countRows(tableName);
    assertThat(count).isEqualTo(2);

    List<Map<String, Object>> results =
        dataTableRowService.queryData(tableName, columnNames, null, 0, 10);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).get("name")).isEqualTo("Alice");
    assertThat(results.get(0).get("value")).isEqualTo(100L);
  }

  @Test
  void addColumn_toExistingTable_success() {
    // Given
    String tableName = "test_add_column";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    DatasetColumnRequest newColumn =
        new DatasetColumnRequest("col2", "Col2", "INTEGER", null, true, true, null);

    // When
    dataTableService.addColumn(tableName, newColumn);

    // Then
    Long columnExists =
        dsl.selectCount()
            .from("information_schema.columns")
            .where(
                "table_schema = 'data' AND table_name = '"
                    + tableName
                    + "' AND column_name = 'col2'")
            .fetchOne(0, Long.class);
    assertThat(columnExists).isEqualTo(1);

    // Verify index was created
    Long indexCount =
        dsl.selectCount()
            .from("pg_indexes")
            .where(
                "schemaname = 'data' AND tablename = '"
                    + tableName
                    + "' AND indexname = 'idx_"
                    + tableName
                    + "_col2'")
            .fetchOne(0, Long.class);
    assertThat(indexCount).isEqualTo(1);
  }

  @Test
  void setColumnIndex_createsAndDropsIndex() {
    // Given
    String tableName = "test_index";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    // When - create index
    dataTableService.setColumnIndex(tableName, "col1", true);

    // Then - index exists
    Long indexCount =
        dsl.selectCount()
            .from("pg_indexes")
            .where(
                "schemaname = 'data' AND tablename = '"
                    + tableName
                    + "' AND indexname = 'idx_"
                    + tableName
                    + "_col1'")
            .fetchOne(0, Long.class);
    assertThat(indexCount).isEqualTo(1);

    // When - drop index
    dataTableService.setColumnIndex(tableName, "col1", false);

    // Then - index removed
    indexCount =
        dsl.selectCount()
            .from("pg_indexes")
            .where(
                "schemaname = 'data' AND tablename = '"
                    + tableName
                    + "' AND indexname = 'idx_"
                    + tableName
                    + "_col1'")
            .fetchOne(0, Long.class);
    assertThat(indexCount).isEqualTo(0);
  }

  @Test
  void dropTable_removesTable() {
    // Given
    String tableName = "test_drop_table";
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    Long tableExists =
        dsl.selectCount()
            .from("information_schema.tables")
            .where("table_schema = 'data' AND table_name = '" + tableName + "'")
            .fetchOne(0, Long.class);
    assertThat(tableExists).isEqualTo(1);

    // When
    dataTableService.dropTable(tableName);

    // Then
    tableExists =
        dsl.selectCount()
            .from("information_schema.tables")
            .where("table_schema = 'data' AND table_name = '" + tableName + "'")
            .fetchOne(0, Long.class);
    assertThat(tableExists).isEqualTo(0);
  }

  @Test
  void truncateTable_removesAllRows() {
    // Given
    String tableName = "test_truncate";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    List<Map<String, Object>> rows = List.of(Map.of("col1", "value1"), Map.of("col1", "value2"));
    dataTableRowService.insertBatch(tableName, List.of("col1"), rows);

    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(2);

    // When
    dataTableRowService.truncateTable(tableName);

    // Then
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(0);
  }

  @Test
  void queryData_withSearch_filtersRows() {
    // Given
    String tableName = "test_search_filter";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    List<String> columnNames = List.of("name", "value");
    List<Map<String, Object>> rows =
        List.of(
            Map.of("name", "Alice", "value", 100),
            Map.of("name", "Bob", "value", 200),
            Map.of("name", "Charlie", "value", 300));
    dataTableRowService.insertBatch(tableName, columnNames, rows);

    // When - search by name
    List<Map<String, Object>> results =
        dataTableRowService.queryData(tableName, columnNames, "Ali", 0, 10);

    // Then
    assertThat(results).hasSize(1);
    assertThat(results.get(0).get("name")).isEqualTo("Alice");

    // When - count with search
    long count = dataTableRowService.countRows(tableName, columnNames, "Ali");
    assertThat(count).isEqualTo(1);

    // When - no match
    List<Map<String, Object>> noMatch =
        dataTableRowService.queryData(tableName, columnNames, "xyz", 0, 10);
    assertThat(noMatch).isEmpty();
    assertThat(dataTableRowService.countRows(tableName, columnNames, "xyz")).isEqualTo(0);

    // When - null search returns all
    List<Map<String, Object>> allRows =
        dataTableRowService.queryData(tableName, columnNames, null, 0, 10);
    assertThat(allRows).hasSize(3);
  }

  @Test
  void queryData_withWildcardInSearch_escapesCorrectly() {
    // Given
    String tableName = "test_search_escape";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    List<String> columnNames = List.of("name");
    List<Map<String, Object>> rows =
        List.of(
            Map.of("name", "100% complete"),
            Map.of("name", "user_name"),
            Map.of("name", "normal text"));
    dataTableRowService.insertBatch(tableName, columnNames, rows);

    // When - search with % should match literally, not as wildcard
    List<Map<String, Object>> percentResults =
        dataTableRowService.queryData(tableName, columnNames, "100%", 0, 10);
    assertThat(percentResults).hasSize(1);
    assertThat(percentResults.get(0).get("name")).isEqualTo("100% complete");

    // When - search with _ should match literally, not as single-char wildcard
    List<Map<String, Object>> underscoreResults =
        dataTableRowService.queryData(tableName, columnNames, "user_", 0, 10);
    assertThat(underscoreResults).hasSize(1);
    assertThat(underscoreResults.get(0).get("name")).isEqualTo("user_name");
  }

  @Test
  void validateName_invalidName_throwsException() {
    // When/Then
    assertThatThrownBy(() -> dataTableService.validateName("Invalid-Name"))
        .isInstanceOf(InvalidTableNameException.class);

    assertThatThrownBy(() -> dataTableService.validateName("9starts_with_number"))
        .isInstanceOf(InvalidTableNameException.class);

    assertThatThrownBy(() -> dataTableService.validateName("Has Space"))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void validateName_validName_noException() {
    // When/Then - should not throw
    dataTableService.validateName("valid_name");
    dataTableService.validateName("validname");
    dataTableService.validateName("valid_name_123");
  }

  // -------------------------------------------------------------------------
  // 1-1. executeQuery (SQL Query) — 7 TC
  // -------------------------------------------------------------------------

  @Test
  void executeQuery_selectAll_returnsRows() {
    // Given
    String tableName = "test_eq_select";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("score", "Score", "INTEGER", null, true, false, null));
    dataTableService.createTable(tableName, columns);
    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "score"),
        List.of(Map.of("name", "Alice", "score", 90), Map.of("name", "Bob", "score", 85)));

    // When
    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT * FROM " + tableName, 100);

    // Then
    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.error()).isNull();
    assertThat(response.affectedRows()).isEqualTo(2);
    assertThat(response.columns()).contains("name", "score");
    assertThat(response.rows()).hasSize(2);
  }

  @Test
  void executeQuery_selectWithLimit_respectsLimit() {
    String tableName = "test_eq_limit";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("val", "Val", "INTEGER", null, true, false, null));
    dataTableService.createTable(tableName, columns);
    dataTableRowService.insertBatch(
        tableName, List.of("val"), List.of(Map.of("val", 1), Map.of("val", 2), Map.of("val", 3)));

    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT * FROM " + tableName + " LIMIT 1", 100);

    assertThat(response.rows()).hasSize(1);
  }

  @Test
  void executeQuery_selectAutoLimit_appliesMaxRows() {
    String tableName = "test_eq_autolimit";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("val", "Val", "INTEGER", null, true, false, null));
    dataTableService.createTable(tableName, columns);
    dataTableRowService.insertBatch(
        tableName, List.of("val"), List.of(Map.of("val", 1), Map.of("val", 2), Map.of("val", 3)));

    // maxRows=2, no LIMIT in SQL — auto LIMIT should be applied
    SqlQueryResponse response = dataTableQueryService.executeQuery("SELECT * FROM " + tableName, 2);

    assertThat(response.rows()).hasSize(2);
  }

  @Test
  void executeQuery_insertDml_returnsAffectedRows() {
    String tableName = "test_eq_insert";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    SqlQueryResponse response =
        dataTableQueryService.executeQuery(
            "INSERT INTO " + tableName + " (name) VALUES ('test1'), ('test2')", 100);

    assertThat(response.queryType()).isEqualTo("INSERT");
    assertThat(response.affectedRows()).isEqualTo(2);
    assertThat(response.error()).isNull();
  }

  @Test
  void executeQuery_syntaxError_throwsUnsafeSqlException() {
    String tableName = "test_eq_syntax";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    // "FORM" 오타는 파싱 자체가 불가능해 이제 SqlValidator 단계에서 UnsafeSqlException 으로 거부된다
    // (#385 Task 3) — 예전처럼 DB 실행까지 도달해 error 필드로 반환되지 않는다.
    assertThatThrownBy(
            () -> dataTableQueryService.executeQuery("SELECT * FORM " + tableName, 100))
        .isInstanceOf(com.smartfirehub.pipeline.exception.UnsafeSqlException.class);
  }

  @Test
  void executeQuery_ddlRejected_throwsSqlQueryException() {
    assertThatThrownBy(
            () -> dataTableQueryService.executeQuery("CREATE TABLE test_ddl (id INT)", 100))
        .isInstanceOf(SqlQueryException.class);
  }

  @Test
  void executeQuery_multiStatement_throwsSqlQueryException() {
    assertThatThrownBy(() -> dataTableQueryService.executeQuery("SELECT 1; SELECT 2", 100))
        .isInstanceOf(SqlQueryException.class);
  }

  // -------------------------------------------------------------------------
  // 1-2. insertRow / updateRow / getRow (Manual Row) — 5 TC
  // -------------------------------------------------------------------------

  @Test
  void insertRow_validData_returnsId() {
    String tableName = "test_ir_valid";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Long id =
        dataTableRowService.insertRow(
            tableName, List.of("name", "age"), Map.of("name", "Alice", "age", 30L));

    assertThat(id).isNotNull().isPositive();
  }

  @Test
  void getRow_existingRow_returnsData() {
    String tableName = "test_gr_existing";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Long id = dataTableRowService.insertRow(tableName, List.of("name"), Map.of("name", "Bob"));

    Map<String, Object> row = dataTableRowService.getRow(tableName, List.of("name"), id);

    assertThat(row.get("name")).isEqualTo("Bob");
    assertThat(row.get("id")).isEqualTo(id);
  }

  @Test
  void updateRow_existingRow_updatesData() {
    String tableName = "test_ur_update";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Long id = dataTableRowService.insertRow(tableName, List.of("name"), Map.of("name", "Before"));
    dataTableRowService.updateRow(tableName, id, List.of("name"), Map.of("name", "After"));

    Map<String, Object> row = dataTableRowService.getRow(tableName, List.of("name"), id);
    assertThat(row.get("name")).isEqualTo("After");
  }

  @Test
  void getRow_nonExistent_throwsException() {
    String tableName = "test_gr_notfound";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    assertThatThrownBy(() -> dataTableRowService.getRow(tableName, List.of("col1"), 99999L))
        .isInstanceOf(RowNotFoundException.class);
  }

  @Test
  void insertRow_nullForNotNull_throwsException() {
    String tableName = "test_ir_notnull";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, Object> data = new java.util.HashMap<>();
    data.put("name", null);

    assertThatThrownBy(() -> dataTableRowService.insertRow(tableName, List.of("name"), data))
        .isInstanceOf(Exception.class);
  }

  // -------------------------------------------------------------------------
  // 1-3. cloneTable — 3 TC
  // -------------------------------------------------------------------------

  @Test
  void cloneTable_withData_copiesAllRows() {
    String sourceTable = "test_clone_src";
    String targetTable = "test_clone_tgt";
    tablesToCleanup.add(sourceTable);
    tablesToCleanup.add(targetTable);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));
    dataTableService.createTable(sourceTable, columns);
    dataTableRowService.insertBatch(
        sourceTable,
        List.of("name", "value"),
        List.of(Map.of("name", "A", "value", 1), Map.of("name", "B", "value", 2)));

    List<DatasetColumnResponse> columnDefs =
        List.of(
            new DatasetColumnResponse(
                1L, "name", "Name", "TEXT", null, true, false, "desc", 0, false),
            new DatasetColumnResponse(
                2L, "value", "Value", "INTEGER", null, true, false, "desc", 1, false));

    dataTableService.cloneTable(sourceTable, targetTable, List.of("name", "value"), columnDefs);

    long count = dataTableRowService.countRows(targetTable);
    assertThat(count).isEqualTo(2);
  }

  @Test
  void cloneTable_emptySource_createsEmptyTable() {
    String sourceTable = "test_clone_empty_src";
    String targetTable = "test_clone_empty_tgt";
    tablesToCleanup.add(sourceTable);
    tablesToCleanup.add(targetTable);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(sourceTable, columns);

    List<DatasetColumnResponse> columnDefs =
        List.of(
            new DatasetColumnResponse(
                1L, "name", "Name", "TEXT", null, true, false, null, 0, false));

    dataTableService.cloneTable(sourceTable, targetTable, List.of("name"), columnDefs);

    long count = dataTableRowService.countRows(targetTable);
    assertThat(count).isEqualTo(0);
  }

  @Test
  void cloneTable_preservesNotNullConstraints() {
    String sourceTable = "test_clone_nn_src";
    String targetTable = "test_clone_nn_tgt";
    tablesToCleanup.add(sourceTable);
    tablesToCleanup.add(targetTable);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("required_col", "Required", "TEXT", null, false, false, null));
    dataTableService.createTable(sourceTable, columns);

    List<DatasetColumnResponse> columnDefs =
        List.of(
            new DatasetColumnResponse(
                1L, "required_col", "Required", "TEXT", null, false, false, null, 0, false));

    dataTableService.cloneTable(sourceTable, targetTable, List.of("required_col"), columnDefs);

    // Attempting to insert NULL into the NOT NULL column should fail
    Map<String, Object> data = new java.util.HashMap<>();
    data.put("required_col", null);
    assertThatThrownBy(
            () -> dataTableRowService.insertRow(targetTable, List.of("required_col"), data))
        .isInstanceOf(Exception.class);
  }

  // -------------------------------------------------------------------------
  // addColumn reserved name validation — #5 fix
  // -------------------------------------------------------------------------

  @Test
  void addColumn_reservedName_id_throwsInvalidTableNameException() {
    // Given
    String tableName = "test_reserved_col";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    DatasetColumnRequest reserved =
        new DatasetColumnRequest("id", "ID", "BIGINT", null, true, false, null);

    // When / Then — 예약어 id는 거부되어야 한다
    assertThatThrownBy(() -> dataTableService.addColumn(tableName, reserved))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("예약어");
  }

  @Test
  void addColumn_reservedName_createdAt_throwsInvalidTableNameException() {
    String tableName = "test_reserved_col2";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    DatasetColumnRequest reserved =
        new DatasetColumnRequest("created_at", "생성일", "TIMESTAMP", null, true, false, null);

    assertThatThrownBy(() -> dataTableService.addColumn(tableName, reserved))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("예약어");
  }

  // -------------------------------------------------------------------------
  // 1-4. executeQuery system column filtering — 1 TC
  // -------------------------------------------------------------------------

  @Test
  void executeQuery_selectAll_filtersSystemColumns() {
    String tableName = "test_eq_syscol";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);
    dataTableRowService.insertBatch(tableName, List.of("name"), List.of(Map.of("name", "test")));

    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT * FROM " + tableName, 100);

    // System columns (id, import_id, created_at) should be filtered out
    assertThat(response.columns()).contains("name");
    assertThat(response.columns()).doesNotContain("id", "import_id", "created_at");
  }

  // -------------------------------------------------------------------------
  // 1-5. 스테이징 스왑 (createTempTable + swapTable) — 1 TC
  //
  // 이 경로는 임시 테이블·전용 시퀀스·원본 DROP·RENAME 을 각각 별개의 이름으로 다루는데, 기존
  // 테스트는 DataTableService 를 모두 목으로 대체해 SQL 수준 커버리지가 0이었다(JaCoCo 실측).
  // 스키마 한정을 한 곳이라도 빠뜨리면 DROP/RENAME 이 조용히 엉뚱한 대상을 향하므로 실제 DB 로 못박는다.
  // -------------------------------------------------------------------------

  @Test
  void createTempTableThenSwap_replacesRowsAndRenamesSequence() {
    String tableName = "test_swap_seq";
    tablesToCleanup.add(tableName);
    dataTableService.createTable(
        tableName,
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)));
    dataTableRowService.insertBatch(tableName, List.of("name"), List.of(Map.of("name", "old")));

    // 스테이징에 새 내용을 적재한 뒤 스왑한다 (REPLACE 적재 전략의 실제 순서)
    dataTableService.createTempTable(tableName);
    dataTableRowService.insertBatch(
        tableName + "_tmp", List.of("name"), List.of(Map.of("name", "new")));
    dataTableService.swapTable(tableName);

    // 원본 이름이 스테이징 내용을 담는다 — DROP 과 RENAME 이 둘 다 올바른 스키마에 닿았다는 증거
    List<String> names = dsl.fetch("SELECT name FROM data.\"" + tableName + "\"").getValues("name", String.class);
    assertThat(names).containsExactly("new");

    // 시퀀스 리네임이 실제로 일어났다 — 빠뜨리면 아무 에러 없이 tmp 이름의 시퀀스가 남고,
    // 다음 createTempTable 의 DROP SEQUENCE IF EXISTS 가 살아있는 시퀀스를 지우려 든다.
    Long canonicalSeq =
        dsl.selectCount()
            .from("pg_sequences")
            .where(
                "schemaname = 'data' AND sequencename = '" + tableName + "_id_seq'")
            .fetchOne(0, Long.class);
    assertThat(canonicalSeq).as("스왑 후 시퀀스는 원본 이름 규약을 따라야 한다").isEqualTo(1);

    Long tmpSeq =
        dsl.selectCount()
            .from("pg_sequences")
            .where("schemaname = 'data' AND sequencename = '" + tableName + "_tmp_id_seq'")
            .fetchOne(0, Long.class);
    assertThat(tmpSeq).as("tmp 시퀀스 이름은 남아 있으면 안 된다").isEqualTo(0);

    // 스왑된 테이블에 계속 삽입할 수 있다 (id DEFAULT nextval 이 살아있는 시퀀스를 가리킨다)
    dataTableRowService.insertBatch(tableName, List.of("name"), List.of(Map.of("name", "after")));
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(2);
  }

  // -------------------------------------------------------------------------
  // 1-6. REPLACE 마무리 정책 (finishReplace) — #685
  //
  // "빈 결과는 기존 데이터를 파괴하지 않는다"는 제품 결정이다. 이 판단이 실행기마다 흩어져 있던
  // 탓에 네 호출부 중 둘이 무조건 스왑했고, 2026-09-18 운영에서 AI 분류가 0행을 내자 빈 임시
  // 테이블이 원본을 덮어 10건이 사라졌다. 이제 판단은 여기 한 곳에 있으므로, 못박는 것도 여기다.
  // 실행기 쪽 테스트는 "행 수를 정확히 넘겼는가"만 본다.
  // -------------------------------------------------------------------------

  @Test
  void finishReplace_withZeroRows_keepsOriginalAndDropsTemp() {
    String tableName = "test_finish_replace_empty";
    tablesToCleanup.add(tableName);
    dataTableService.createTable(
        tableName,
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)));
    dataTableRowService.insertBatch(tableName, List.of("name"), List.of(Map.of("name", "keep-me")));

    // 적재 결과가 0행인 REPLACE — 임시 테이블은 비어 있다
    dataTableService.createTempTable(tableName);
    dataTableService.finishReplace(tableName, 0);

    List<String> names =
        dsl.fetch("SELECT name FROM data.\"" + tableName + "\"").getValues("name", String.class);
    assertThat(names).as("빈 결과가 기존 데이터를 지워서는 안 된다").containsExactly("keep-me");

    Long tmpTable =
        dsl.selectCount()
            .from("pg_tables")
            .where("schemaname = 'data' AND tablename = '" + tableName + "_tmp'")
            .fetchOne(0, Long.class);
    assertThat(tmpTable).as("임시 테이블은 정리돼야 한다 — 남으면 다음 실행이 걸린다").isEqualTo(0);
  }

  @Test
  void finishReplace_withRows_swapsTempOverOriginal() {
    String tableName = "test_finish_replace_rows";
    tablesToCleanup.add(tableName);
    dataTableService.createTable(
        tableName,
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)));
    dataTableRowService.insertBatch(tableName, List.of("name"), List.of(Map.of("name", "old")));

    dataTableService.createTempTable(tableName);
    dataTableRowService.insertBatch(
        tableName + "_tmp", List.of("name"), List.of(Map.of("name", "new")));
    dataTableService.finishReplace(tableName, 1);

    List<String> names =
        dsl.fetch("SELECT name FROM data.\"" + tableName + "\"").getValues("name", String.class);
    assertThat(names).as("적재된 행이 있으면 REPLACE 는 평소대로 맞바꾼다").containsExactly("new");
  }

  // -------------------------------------------------------------------------
  // 1-7. _updated_at 증분 처리 기반 (V123 Task 2) — 4 TC
  //
  // Task 1(V123)은 기존 테이블을 백필했다. 여기서는 "새로 만드는" 모든 경로
  // (createTable/createTempTable+swapTable/cloneTable)가 트리거·컬럼·인덱스를 빠짐없이
  // 갖추는지 못박는다.
  // -------------------------------------------------------------------------

  /** 테스트에서 반복되는 "이름 하나짜리 TEXT 컬럼" 요청을 만드는 헬퍼. */
  private DatasetColumnRequest textColumn(String name) {
    return new DatasetColumnRequest(name, name, "TEXT", null, true, false, null);
  }

  @Test
  void 새_테이블에_updated_at_컬럼과_트리거가_생성되고_UPDATE시_갱신된다() {
    String table = "t_upd_" + System.nanoTime();
    tablesToCleanup.add(table);
    dataTableService.createTable(table, List.of(textColumn("name")));
    String q = DataSchema.qualify(table);
    dsl.execute("INSERT INTO " + q + " (name) VALUES ('a')");
    java.time.OffsetDateTime before =
        (java.time.OffsetDateTime) dsl.fetchValue("SELECT _updated_at FROM " + q);
    // 별도 트랜잭션에서 수정해야 now() 가 달라진다 — 이 테스트는 IntegrationTestBase 의 테스트
    // 트랜잭션 안에서 실행되므로 now()(=트랜잭션 시작 시각)가 동일할 수 있다. 그래서 아래 단언은
    // 의도적으로 약하다(isAfterOrEqualTo) — 실제 트리거 동작 증명은 다음 단언(명시값 덮어쓰기)이 한다.
    dsl.execute("UPDATE " + q + " SET name = 'b'");
    java.time.OffsetDateTime after =
        (java.time.OffsetDateTime) dsl.fetchValue("SELECT _updated_at FROM " + q);
    assertThat(after).isAfterOrEqualTo(before);
    // 명시값을 넣어도 트리거가 덮어쓴다
    dsl.execute("UPDATE " + q + " SET _updated_at = '2000-01-01'::timestamptz");
    java.time.OffsetDateTime forced =
        (java.time.OffsetDateTime) dsl.fetchValue("SELECT _updated_at FROM " + q);
    assertThat(forced.getYear()).isNotEqualTo(2000);
  }

  @Test
  void 예약_컬럼명_updated_at은_거부된다() {
    assertThatThrownBy(
            () ->
                dataTableService.createTable(
                    "t_rsv_" + System.nanoTime(), List.of(textColumn("_updated_at"))))
        .hasMessageContaining("_updated_at");
  }

  @Test
  void 임시테이블_스왑_후에도_트리거가_유지된다() {
    String table = "t_swap_upd_" + System.nanoTime();
    tablesToCleanup.add(table);
    dataTableService.createTable(table, List.of(textColumn("name")));
    dataTableService.createTempTable(table);
    dataTableService.swapTable(table);
    Long triggers =
        (Long)
            dsl.fetchValue(
                "select count(*) from pg_trigger where tgname='fh_touch_updated_at' and tgrelid = to_regclass(?)",
                DataSchema.qualify(table));
    assertThat(triggers).isEqualTo(1);
  }

  @Test
  void 복제_테이블은_updated_at_기본값과_트리거를_가진다() {
    String src = "t_src_upd_" + System.nanoTime();
    String dst = "t_dst_upd_" + System.nanoTime();
    tablesToCleanup.add(src);
    tablesToCleanup.add(dst);
    dataTableService.createTable(src, List.of(textColumn("name")));
    dsl.execute("INSERT INTO " + DataSchema.qualify(src) + " (name) VALUES ('a')");
    List<DatasetColumnResponse> columnDefs =
        List.of(new DatasetColumnResponse(1L, "name", "name", "TEXT", null, true, false, null, 0, false));
    dataTableService.cloneTable(src, dst, List.of("name"), columnDefs);
    dsl.execute("INSERT INTO " + DataSchema.qualify(dst) + " (name) VALUES ('b')");
    Long nulls =
        (Long)
            dsl.fetchValue(
                "select count(*) from " + DataSchema.qualify(dst) + " where _updated_at is null");
    assertThat(nulls).isZero();

    Long triggers =
        (Long)
            dsl.fetchValue(
                "select count(*) from pg_trigger where tgname='fh_touch_updated_at' and tgrelid = to_regclass(?)",
                DataSchema.qualify(dst));
    assertThat(triggers).isEqualTo(1);
  }

  @Test
  void createTable_rejectsReservedSearchPrefix() {
    assertThatThrownBy(() -> dataTableService.createTable("fh_search_1",
            List.of(new DatasetColumnRequest("a", "A", "TEXT", null, true, false, null))))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void cloneTable_rejectsReservedSearchPrefixTarget() {
    // 복제 대상 이름도 색인 테이블 이름 공간(fh_search_*)을 침범하지 못한다 — SQL 실행 전에 거부
    assertThatThrownBy(
            () -> dataTableService.cloneTable("any_source", "fh_search_2", List.of("a"), List.of()))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("fh_search_");
  }
}
