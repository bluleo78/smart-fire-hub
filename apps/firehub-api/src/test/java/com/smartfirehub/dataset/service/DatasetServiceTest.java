package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DatasetServiceTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DatasetFavoriteService datasetFavoriteService;
  @Autowired private DatasetTagService datasetTagService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long testCategoryId;

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "testuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Test User")
            .set(USER.EMAIL, "test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // Create test category
    testCategoryId =
        dsl.insertInto(DATASET_CATEGORY)
            .set(DATASET_CATEGORY.NAME, "Test Category")
            .set(DATASET_CATEGORY.DESCRIPTION, "Test Description")
            .returning(DATASET_CATEGORY.ID)
            .fetchOne()
            .getId();
  }

  @Test
  void createDataset_withColumns_success() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, false, true, "Name column"),
            new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, "Age column"));

    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "Test Dataset", "test_dataset", "Test description", testCategoryId, "SOURCE", columns);

    // When
    DatasetDetailResponse response = datasetService.createDataset(request, testUserId);

    // Then
    assertThat(response.id()).isNotNull();
    assertThat(response.name()).isEqualTo("Test Dataset");
    assertThat(response.tableName()).isEqualTo("test_dataset");
    assertThat(response.columns()).hasSize(2);

    // Verify in DB
    Long count =
        dsl.selectCount()
            .from(DATASET)
            .where(DATASET.NAME.eq("Test Dataset"))
            .fetchOne(0, Long.class);
    assertThat(count).isEqualTo(1);

    Long columnCount =
        dsl.selectCount()
            .from(DATASET_COLUMN)
            .where(DATASET_COLUMN.DATASET_ID.eq(response.id()))
            .fetchOne(0, Long.class);
    assertThat(columnCount).isEqualTo(2);

    // Verify physical table exists
    Long tableExists =
        dsl.selectCount()
            .from("information_schema.tables")
            .where("table_schema = 'data' AND table_name = 'test_dataset'")
            .fetchOne(0, Long.class);
    assertThat(tableExists).isEqualTo(1);
  }

  @Test
  void createDataset_duplicateName_throwsException() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    CreateDatasetRequest request1 =
        new CreateDatasetRequest("Duplicate", "table1", null, null, "SOURCE", columns);

    datasetService.createDataset(request1, testUserId);

    CreateDatasetRequest request2 =
        new CreateDatasetRequest("Duplicate", "table2", null, null, "SOURCE", columns);

    // When/Then
    assertThatThrownBy(() -> datasetService.createDataset(request2, testUserId))
        .isInstanceOf(DuplicateDatasetNameException.class);
  }

  @Test
  void getDatasets_withFilters_returnsFilteredList() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    datasetService.createDataset(
        new CreateDatasetRequest("Dataset A", "dataset_a", null, testCategoryId, "SOURCE", columns),
        testUserId);

    datasetService.createDataset(
        new CreateDatasetRequest("Dataset B", "dataset_b", null, null, "DERIVED", columns),
        testUserId);

    // When
    PageResponse<DatasetResponse> result =
        datasetService.getDatasets(testCategoryId, null, null, 0, 10);

    // Then
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("Dataset A");
  }

  @Test
  void updateDataset_success() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Original Name", "original_table", "Original description", null, "SOURCE", columns),
            testUserId);

    UpdateDatasetRequest updateRequest =
        new UpdateDatasetRequest("Updated Name", "Updated description", testCategoryId);

    // When
    datasetService.updateDataset(dataset.id(), updateRequest, testUserId);

    // Then
    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.name()).isEqualTo("Updated Name");
    assertThat(updated.description()).isEqualTo("Updated description");
    assertThat(updated.category()).isNotNull();
    assertThat(updated.category().id()).isEqualTo(testCategoryId);
  }

  @Test
  void addColumn_toExistingDataset_success() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("Test Dataset", "test_table", null, null, "SOURCE", columns),
            testUserId);

    AddColumnRequest addColumnRequest =
        new AddColumnRequest(
            "new_column", "New Column", "INTEGER", null, true, false, "New column description");

    // When
    DatasetColumnResponse newColumn = datasetService.addColumn(dataset.id(), addColumnRequest);

    // Then
    assertThat(newColumn.columnName()).isEqualTo("new_column");
    assertThat(newColumn.displayName()).isEqualTo("New Column");

    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.columns()).hasSize(2);
  }

  @Test
  void reorderColumns_success() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("col_a", "Column A", "TEXT", null, true, false, null),
            new DatasetColumnRequest("col_b", "Column B", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("col_c", "Column C", "BOOLEAN", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("Reorder Test", "reorder_test", null, null, "SOURCE", columns),
            testUserId);

    // Original order: col_a(0), col_b(1), col_c(2)
    List<DatasetColumnResponse> originalColumns = dataset.columns();
    assertThat(originalColumns).hasSize(3);
    assertThat(originalColumns.get(0).columnName()).isEqualTo("col_a");
    assertThat(originalColumns.get(1).columnName()).isEqualTo("col_b");
    assertThat(originalColumns.get(2).columnName()).isEqualTo("col_c");

    // Reorder to: col_c, col_a, col_b
    List<Long> reorderedIds =
        List.of(
            originalColumns.get(2).id(), originalColumns.get(0).id(), originalColumns.get(1).id());

    // When
    datasetService.reorderColumns(dataset.id(), new ReorderColumnsRequest(reorderedIds));

    // Then
    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.columns().get(0).columnName()).isEqualTo("col_c");
    assertThat(updated.columns().get(1).columnName()).isEqualTo("col_a");
    assertThat(updated.columns().get(2).columnName()).isEqualTo("col_b");
  }

  @Test
  void reorderColumns_missingColumn_throwsException() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("col_a", "Column A", "TEXT", null, true, false, null),
            new DatasetColumnRequest("col_b", "Column B", "INTEGER", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Reorder Fail Test", "reorder_fail_test", null, null, "SOURCE", columns),
            testUserId);

    List<Long> incompleteIds = List.of(dataset.columns().get(0).id());

    // When/Then
    assertThatThrownBy(
            () ->
                datasetService.reorderColumns(
                    dataset.id(), new ReorderColumnsRequest(incompleteIds)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Column IDs must match exactly");
  }

  @Test
  void reorderColumns_duplicateIds_throwsException() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("col_a", "Column A", "TEXT", null, true, false, null),
            new DatasetColumnRequest("col_b", "Column B", "INTEGER", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Reorder Dup Test", "reorder_dup_test", null, null, "SOURCE", columns),
            testUserId);

    Long firstColId = dataset.columns().get(0).id();
    List<Long> duplicateIds = List.of(firstColId, firstColId);

    // When/Then
    assertThatThrownBy(
            () ->
                datasetService.reorderColumns(
                    dataset.id(), new ReorderColumnsRequest(duplicateIds)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate column IDs");
  }

  @Test
  void reorderColumns_nonExistentDataset_throwsException() {
    // When/Then
    assertThatThrownBy(
            () ->
                datasetService.reorderColumns(999999L, new ReorderColumnsRequest(List.of(1L, 2L))))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  @Test
  void deleteDataset_removesDataAndTable() {
    // Given
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("To Delete", "to_delete", null, null, "SOURCE", columns),
            testUserId);

    Long datasetId = dataset.id();

    // When
    datasetService.deleteDataset(datasetId);

    // Then
    assertThatThrownBy(() -> datasetService.getDatasetById(datasetId))
        .isInstanceOf(DatasetNotFoundException.class);

    // Verify columns deleted
    Long columnCount =
        dsl.selectCount()
            .from(DATASET_COLUMN)
            .where(DATASET_COLUMN.DATASET_ID.eq(datasetId))
            .fetchOne(0, Long.class);
    assertThat(columnCount).isEqualTo(0);

    // Verify physical table dropped
    Long tableExists =
        dsl.selectCount()
            .from("information_schema.tables")
            .where("table_schema = 'data' AND table_name = 'to_delete'")
            .fetchOne(0, Long.class);
    assertThat(tableExists).isEqualTo(0);
  }

  // =========================================================================
  // Helper
  // =========================================================================

  private DatasetDetailResponse createTestDatasetWithData(String name, String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(name, tableName, null, testCategoryId, "SOURCE", columns),
            testUserId);

    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Alice", "value", 100)));
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Bob", "value", 200)));

    return datasetService.getDatasetById(dataset.id());
  }

  // =========================================================================
  // 2-1. SQL Query
  // =========================================================================

  @Test
  void executeQuery_selectOnDataset_success() {
    DatasetDetailResponse dataset = createTestDatasetWithData("Query Test", "query_test");
    SqlQueryRequest request = new SqlQueryRequest("SELECT * FROM data.query_test", 100);

    SqlQueryResponse response = datasetDataService.executeQuery(dataset.id(), request, testUserId);

    assertThat(response.queryType()).isEqualTo("SELECT");
    assertThat(response.error()).isNull();
    assertThat(response.rows()).hasSize(2);
  }

  @Test
  void executeQuery_insertOnDataset_success() {
    DatasetDetailResponse dataset =
        createTestDatasetWithData("Insert Query Test", "insert_query_test");
    SqlQueryRequest request =
        new SqlQueryRequest(
            "INSERT INTO data.insert_query_test (name, value) VALUES ('Charlie', 300)", 100);

    SqlQueryResponse response = datasetDataService.executeQuery(dataset.id(), request, testUserId);

    assertThat(response.queryType()).isEqualTo("INSERT");
    assertThat(response.affectedRows()).isEqualTo(1);
    assertThat(response.error()).isNull();
  }

  @Test
  void executeQuery_syntaxError_savesHistory() {
    DatasetDetailResponse dataset =
        createTestDatasetWithData("Syntax Error Test", "syntax_error_test");
    SqlQueryRequest request = new SqlQueryRequest("SELECT * FORM syntax_error_test", 100);

    SqlQueryResponse response = datasetDataService.executeQuery(dataset.id(), request, testUserId);

    assertThat(response.error()).isNotNull();

    PageResponse<QueryHistoryResponse> history =
        datasetDataService.getQueryHistory(dataset.id(), 0, 10);
    assertThat(history.content()).isNotEmpty();
    boolean foundFailed = history.content().stream().anyMatch(h -> !h.success());
    assertThat(foundFailed).isTrue();
  }

  @Test
  void executeQuery_nonExistentDataset_throwsNotFound() {
    SqlQueryRequest request = new SqlQueryRequest("SELECT 1", 100);

    assertThatThrownBy(() -> datasetDataService.executeQuery(999999L, request, testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  @Test
  void getQueryHistory_returnsPagedResults() {
    DatasetDetailResponse dataset = createTestDatasetWithData("History Test", "history_test");

    datasetDataService.executeQuery(
        dataset.id(), new SqlQueryRequest("SELECT * FROM data.history_test", 100), testUserId);
    datasetDataService.executeQuery(
        dataset.id(),
        new SqlQueryRequest("SELECT * FROM data.history_test LIMIT 1", 100),
        testUserId);

    PageResponse<QueryHistoryResponse> history =
        datasetDataService.getQueryHistory(dataset.id(), 0, 10);

    assertThat(history.content()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(history.totalElements()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void getQueryHistory_emptyDataset_returnsEmptyPage() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Empty History", "empty_history", null, null, "SOURCE", columns),
            testUserId);

    PageResponse<QueryHistoryResponse> history =
        datasetDataService.getQueryHistory(dataset.id(), 0, 10);

    assertThat(history.content()).isEmpty();
    assertThat(history.totalElements()).isEqualTo(0);
  }

  // =========================================================================
  // 2-2. Manual Row Entry
  // =========================================================================

  @Test
  void addRow_validData_returnsRowData() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("AddRow Test", "addrow_test", null, null, "SOURCE", columns),
            testUserId);

    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("name", "Alice", "age", 25)));

    assertThat(row.id()).isNotNull().isPositive();
    assertThat(row.data().get("name")).isEqualTo("Alice");
  }

  @Test
  void addRow_invalidType_throwsException() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("score", "Score", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "InvalidType Test", "invalidtype_test", null, null, "SOURCE", columns),
            testUserId);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("score", "not_a_number"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateRow_existingRow_success() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "UpdateRow Test", "updaterow_test", null, null, "SOURCE", columns),
            testUserId);

    RowDataResponse added =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "Before")));

    datasetDataService.updateRow(
        dataset.id(), added.id(), new RowDataRequest(Map.of("name", "After")));

    RowDataResponse updated = datasetDataService.getRow(dataset.id(), added.id());
    assertThat(updated.data().get("name")).isEqualTo("After");
  }

  @Test
  void getRow_existingRow_returnsData() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("title", "Title", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("GetRow Test", "getrow_test", null, null, "SOURCE", columns),
            testUserId);

    RowDataResponse added =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("title", "Hello World")));

    RowDataResponse row = datasetDataService.getRow(dataset.id(), added.id());

    assertThat(row.id()).isEqualTo(added.id());
    assertThat(row.data().get("title")).isEqualTo("Hello World");
  }

  @Test
  void addRow_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(
            () -> datasetDataService.addRow(999999L, new RowDataRequest(Map.of("col1", "value"))))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // 2-3. Clone Dataset
  // =========================================================================

  @Test
  void cloneDataset_withData_success() {
    DatasetDetailResponse source = createTestDatasetWithData("Clone Source", "clone_source");

    CloneDatasetRequest cloneRequest =
        new CloneDatasetRequest("Clone Target", "clone_target", null, true, false);

    DatasetDetailResponse cloned =
        datasetService.cloneDataset(source.id(), cloneRequest, testUserId);

    assertThat(cloned.name()).isEqualTo("Clone Target");
    assertThat(cloned.tableName()).isEqualTo("clone_target");
    assertThat(cloned.rowCount()).isEqualTo(source.rowCount());
    assertThat(cloned.columns()).hasSameSizeAs(source.columns());
  }

  @Test
  void cloneDataset_schemaOnly_success() {
    DatasetDetailResponse source =
        createTestDatasetWithData("Clone Schema Src", "clone_schema_src");

    CloneDatasetRequest cloneRequest =
        new CloneDatasetRequest("Clone Schema Tgt", "clone_schema_tgt", null, false, false);

    DatasetDetailResponse cloned =
        datasetService.cloneDataset(source.id(), cloneRequest, testUserId);

    assertThat(cloned.rowCount()).isEqualTo(0);
    assertThat(cloned.columns()).hasSameSizeAs(source.columns());
  }

  @Test
  void cloneDataset_withTags_copiesTags() {
    DatasetDetailResponse source = createTestDatasetWithData("Clone Tag Src", "clone_tag_src");
    datasetTagService.addTag(source.id(), "important", testUserId);
    datasetTagService.addTag(source.id(), "production", testUserId);

    CloneDatasetRequest cloneRequest =
        new CloneDatasetRequest("Clone Tag Tgt", "clone_tag_tgt", null, false, true);

    DatasetDetailResponse cloned =
        datasetService.cloneDataset(source.id(), cloneRequest, testUserId);

    assertThat(cloned.tags()).containsExactlyInAnyOrder("important", "production");
  }

  @Test
  void cloneDataset_duplicateName_throwsException() {
    DatasetDetailResponse source = createTestDatasetWithData("Clone Dup Src", "clone_dup_src");

    CloneDatasetRequest clone1 =
        new CloneDatasetRequest("Clone Dup Tgt", "clone_dup_tgt", null, false, false);
    datasetService.cloneDataset(source.id(), clone1, testUserId);

    CloneDatasetRequest clone2 =
        new CloneDatasetRequest("Clone Dup Tgt", "clone_dup_tgt2", null, false, false);
    assertThatThrownBy(() -> datasetService.cloneDataset(source.id(), clone2, testUserId))
        .isInstanceOf(DuplicateDatasetNameException.class);
  }

  @Test
  void cloneDataset_nonExistentSource_throwsNotFound() {
    CloneDatasetRequest cloneRequest =
        new CloneDatasetRequest("No Source Clone", "no_source_clone", null, false, false);

    assertThatThrownBy(() -> datasetService.cloneDataset(999999L, cloneRequest, testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // 2-4. Existing untested methods
  // =========================================================================

  @Test
  void getDatasetById_success() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse created =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "GetById Test", "getbyid_test", "desc", null, "SOURCE", columns),
            testUserId);

    DatasetDetailResponse found = datasetService.getDatasetById(created.id());

    assertThat(found.id()).isEqualTo(created.id());
    assertThat(found.name()).isEqualTo("GetById Test");
    assertThat(found.description()).isEqualTo("desc");
  }

  @Test
  void getDatasetById_notFound_throwsException() {
    assertThatThrownBy(() -> datasetService.getDatasetById(999999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  @Test
  void deleteColumn_success() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null),
            new DatasetColumnRequest("col2", "Col2", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("DelCol Test", "delcol_test", null, null, "SOURCE", columns),
            testUserId);

    Long col2Id =
        dataset.columns().stream()
            .filter(c -> c.columnName().equals("col2"))
            .findFirst()
            .get()
            .id();

    datasetService.deleteColumn(dataset.id(), col2Id);

    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.columns()).hasSize(1);
    assertThat(updated.columns().get(0).columnName()).isEqualTo("col1");
  }

  @Test
  void deleteColumn_lastColumn_throwsException() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("only_col", "Only Col", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "DelLastCol Test", "dellastcol_test", null, null, "SOURCE", columns),
            testUserId);

    Long onlyColId = dataset.columns().get(0).id();

    assertThatThrownBy(() -> datasetService.deleteColumn(dataset.id(), onlyColId))
        .isInstanceOf(ColumnModificationException.class);
  }

  @Test
  void toggleFavorite_togglesState() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Favorite Test", "favorite_test", null, null, "SOURCE", columns),
            testUserId);

    FavoriteToggleResponse result1 =
        datasetFavoriteService.toggleFavorite(dataset.id(), testUserId);
    assertThat(result1.favorited()).isTrue();

    FavoriteToggleResponse result2 =
        datasetFavoriteService.toggleFavorite(dataset.id(), testUserId);
    assertThat(result2.favorited()).isFalse();
  }

  @Test
  void addTag_success() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("Tag Test", "tag_test", null, null, "SOURCE", columns),
            testUserId);

    datasetTagService.addTag(dataset.id(), "test-tag", testUserId);

    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.tags()).contains("test-tag");
  }

  @Test
  void addTag_duplicate_throwsException() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("DupTag Test", "duptag_test", null, null, "SOURCE", columns),
            testUserId);

    datasetTagService.addTag(dataset.id(), "dup-tag", testUserId);

    assertThatThrownBy(() -> datasetTagService.addTag(dataset.id(), "dup-tag", testUserId))
        .isInstanceOf(IllegalStateException.class);
  }

  // =========================================================================
  // 2-5. Batch Row Entry
  // =========================================================================

  @Test
  void addRowsBatch_multipleRows_success() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest("Batch Test", "batch_test", null, null, "SOURCE", columns),
            testUserId);

    List<Map<String, Object>> rows =
        List.of(
            Map.of("name", "Alice", "value", 100),
            Map.of("name", "Bob", "value", 200),
            Map.of("name", "Charlie", "value", 300));

    BatchRowDataResponse response =
        datasetDataService.addRowsBatch(dataset.id(), new BatchRowDataRequest(rows));

    assertThat(response.insertedCount()).isEqualTo(3);

    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.rowCount()).isEqualTo(3);
  }

  @Test
  void addRowsBatch_invalidDataType_throwsException() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("score", "Score", "INTEGER", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "BatchInvalid Test", "batchinvalid_test", null, null, "SOURCE", columns),
            testUserId);

    List<Map<String, Object>> rows = List.of(Map.of("score", 100), Map.of("score", "not_a_number"));

    assertThatThrownBy(
            () -> datasetDataService.addRowsBatch(dataset.id(), new BatchRowDataRequest(rows)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addRowsBatch_nonExistentDataset_throwsNotFound() {
    List<Map<String, Object>> rows = List.of(Map.of("col1", "value"));

    assertThatThrownBy(
            () -> datasetDataService.addRowsBatch(999999L, new BatchRowDataRequest(rows)))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  @Test
  void deleteDataRows_success() {
    DatasetDetailResponse dataset = createTestDatasetWithData("DelRows Test", "delrows_test");

    DataQueryResponse dataResponse = datasetDataService.getDatasetData(dataset.id(), null, 0, 10);
    List<Long> rowIds =
        dataResponse.rows().stream().map(r -> ((Number) r.get("_id")).longValue()).toList();

    assertThat(rowIds).isNotEmpty();

    DataDeleteResponse result = datasetDataService.deleteDataRows(dataset.id(), rowIds);
    assertThat(result.deletedCount()).isEqualTo(rowIds.size());

    DatasetDetailResponse updated = datasetService.getDatasetById(dataset.id());
    assertThat(updated.rowCount()).isEqualTo(0);
  }
}
