package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class DataTableServiceTest extends IntegrationTestBase {

    @Autowired
    private DataTableService dataTableService;

    @Autowired
    private DSLContext dsl;

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

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", false, true, null),
                new DatasetColumnRequest("age", "Age", "INTEGER", true, false, null),
                new DatasetColumnRequest("score", "Score", "DECIMAL", true, false, null),
                new DatasetColumnRequest("active", "Active", "BOOLEAN", true, false, null)
        );

        // When
        dataTableService.createTable(tableName, columns);

        // Then
        Long tableExists = dsl.selectCount()
                .from("information_schema.tables")
                .where("table_schema = 'data' AND table_name = '" + tableName + "'")
                .fetchOne(0, Long.class);
        assertThat(tableExists).isEqualTo(1);

        // Verify columns exist
        Long columnCount = dsl.selectCount()
                .from("information_schema.columns")
                .where("table_schema = 'data' AND table_name = '" + tableName + "'")
                .fetchOne(0, Long.class);
        // id, import_id, created_at + 4 custom columns = 7
        assertThat(columnCount).isGreaterThanOrEqualTo(4);

        // Verify index was created for indexed column
        Long indexCount = dsl.selectCount()
                .from("pg_indexes")
                .where("schemaname = 'data' AND tablename = '" + tableName + "' AND indexname = 'idx_" + tableName + "_name'")
                .fetchOne(0, Long.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void insertBatch_andQueryData_success() {
        // Given
        String tableName = "test_insert_query";
        tablesToCleanup.add(tableName);

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", true, false, null),
                new DatasetColumnRequest("value", "Value", "INTEGER", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        List<String> columnNames = List.of("name", "value");
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "Alice", "value", 100),
                Map.of("name", "Bob", "value", 200)
        );

        // When
        dataTableService.insertBatch(tableName, columnNames, rows);

        // Then
        long count = dataTableService.countRows(tableName);
        assertThat(count).isEqualTo(2);

        List<Map<String, Object>> results = dataTableService.queryData(tableName, columnNames, null, 0, 10);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("name")).isEqualTo("Alice");
        assertThat(results.get(0).get("value")).isEqualTo(100L);
    }

    @Test
    void addColumn_toExistingTable_success() {
        // Given
        String tableName = "test_add_column";
        tablesToCleanup.add(tableName);

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        DatasetColumnRequest newColumn = new DatasetColumnRequest(
                "col2",
                "Col2",
                "INTEGER",
                true,
                true,
                null
        );

        // When
        dataTableService.addColumn(tableName, newColumn);

        // Then
        Long columnExists = dsl.selectCount()
                .from("information_schema.columns")
                .where("table_schema = 'data' AND table_name = '" + tableName + "' AND column_name = 'col2'")
                .fetchOne(0, Long.class);
        assertThat(columnExists).isEqualTo(1);

        // Verify index was created
        Long indexCount = dsl.selectCount()
                .from("pg_indexes")
                .where("schemaname = 'data' AND tablename = '" + tableName + "' AND indexname = 'idx_" + tableName + "_col2'")
                .fetchOne(0, Long.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void setColumnIndex_createsAndDropsIndex() {
        // Given
        String tableName = "test_index";
        tablesToCleanup.add(tableName);

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        // When - create index
        dataTableService.setColumnIndex(tableName, "col1", true);

        // Then - index exists
        Long indexCount = dsl.selectCount()
                .from("pg_indexes")
                .where("schemaname = 'data' AND tablename = '" + tableName + "' AND indexname = 'idx_" + tableName + "_col1'")
                .fetchOne(0, Long.class);
        assertThat(indexCount).isEqualTo(1);

        // When - drop index
        dataTableService.setColumnIndex(tableName, "col1", false);

        // Then - index removed
        indexCount = dsl.selectCount()
                .from("pg_indexes")
                .where("schemaname = 'data' AND tablename = '" + tableName + "' AND indexname = 'idx_" + tableName + "_col1'")
                .fetchOne(0, Long.class);
        assertThat(indexCount).isEqualTo(0);
    }

    @Test
    void dropTable_removesTable() {
        // Given
        String tableName = "test_drop_table";
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        Long tableExists = dsl.selectCount()
                .from("information_schema.tables")
                .where("table_schema = 'data' AND table_name = '" + tableName + "'")
                .fetchOne(0, Long.class);
        assertThat(tableExists).isEqualTo(1);

        // When
        dataTableService.dropTable(tableName);

        // Then
        tableExists = dsl.selectCount()
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

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        List<Map<String, Object>> rows = List.of(
                Map.of("col1", "value1"),
                Map.of("col1", "value2")
        );
        dataTableService.insertBatch(tableName, List.of("col1"), rows);

        assertThat(dataTableService.countRows(tableName)).isEqualTo(2);

        // When
        dataTableService.truncateTable(tableName);

        // Then
        assertThat(dataTableService.countRows(tableName)).isEqualTo(0);
    }

    @Test
    void queryData_withSearch_filtersRows() {
        // Given
        String tableName = "test_search_filter";
        tablesToCleanup.add(tableName);

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", true, false, null),
                new DatasetColumnRequest("value", "Value", "INTEGER", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        List<String> columnNames = List.of("name", "value");
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "Alice", "value", 100),
                Map.of("name", "Bob", "value", 200),
                Map.of("name", "Charlie", "value", 300)
        );
        dataTableService.insertBatch(tableName, columnNames, rows);

        // When - search by name
        List<Map<String, Object>> results = dataTableService.queryData(tableName, columnNames, "Ali", 0, 10);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("Alice");

        // When - count with search
        long count = dataTableService.countRows(tableName, columnNames, "Ali");
        assertThat(count).isEqualTo(1);

        // When - no match
        List<Map<String, Object>> noMatch = dataTableService.queryData(tableName, columnNames, "xyz", 0, 10);
        assertThat(noMatch).isEmpty();
        assertThat(dataTableService.countRows(tableName, columnNames, "xyz")).isEqualTo(0);

        // When - null search returns all
        List<Map<String, Object>> allRows = dataTableService.queryData(tableName, columnNames, null, 0, 10);
        assertThat(allRows).hasSize(3);
    }

    @Test
    void queryData_withWildcardInSearch_escapesCorrectly() {
        // Given
        String tableName = "test_search_escape";
        tablesToCleanup.add(tableName);

        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", true, false, null)
        );

        dataTableService.createTable(tableName, columns);

        List<String> columnNames = List.of("name");
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "100% complete"),
                Map.of("name", "user_name"),
                Map.of("name", "normal text")
        );
        dataTableService.insertBatch(tableName, columnNames, rows);

        // When - search with % should match literally, not as wildcard
        List<Map<String, Object>> percentResults = dataTableService.queryData(tableName, columnNames, "100%", 0, 10);
        assertThat(percentResults).hasSize(1);
        assertThat(percentResults.get(0).get("name")).isEqualTo("100% complete");

        // When - search with _ should match literally, not as single-char wildcard
        List<Map<String, Object>> underscoreResults = dataTableService.queryData(tableName, columnNames, "user_", 0, 10);
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
}
