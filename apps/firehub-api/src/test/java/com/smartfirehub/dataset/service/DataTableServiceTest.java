package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.exception.SqlQueryException;
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
    SqlQueryResponse response = dataTableQueryService.executeQuery("SELECT * FROM " + tableName, 100);

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
  void executeQuery_syntaxError_returnsErrorMessage() {
    String tableName = "test_eq_syntax";
    tablesToCleanup.add(tableName);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    SqlQueryResponse response =
        dataTableQueryService.executeQuery("SELECT * FORM " + tableName, 100);

    assertThat(response.error()).isNotNull();
    assertThat(response.rows()).isEmpty();
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

    Long id =
        dataTableRowService.insertRow(tableName, List.of("name"), Map.of("name", "Before"));
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
}
