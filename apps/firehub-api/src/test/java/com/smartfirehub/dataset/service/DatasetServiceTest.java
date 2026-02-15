package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class DatasetServiceTest extends IntegrationTestBase {

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private DSLContext dsl;

    private Long testUserId;
    private Long testCategoryId;

    @BeforeEach
    void setUp() {
        // Create test user
        testUserId = dsl.insertInto(USER)
                .set(USER.USERNAME, "testuser")
                .set(USER.PASSWORD, "password")
                .set(USER.NAME, "Test User")
                .set(USER.EMAIL, "test@example.com")
                .returning(USER.ID)
                .fetchOne()
                .getId();

        // Create test category
        testCategoryId = dsl.insertInto(DATASET_CATEGORY)
                .set(DATASET_CATEGORY.NAME, "Test Category")
                .set(DATASET_CATEGORY.DESCRIPTION, "Test Description")
                .returning(DATASET_CATEGORY.ID)
                .fetchOne()
                .getId();
    }

    @Test
    void createDataset_withColumns_success() {
        // Given
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", null, false, true, "Name column"),
                new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, "Age column")
        );

        CreateDatasetRequest request = new CreateDatasetRequest(
                "Test Dataset",
                "test_dataset",
                "Test description",
                testCategoryId,
                "SOURCE",
                columns
        );

        // When
        DatasetDetailResponse response = datasetService.createDataset(request, testUserId);

        // Then
        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Test Dataset");
        assertThat(response.tableName()).isEqualTo("test_dataset");
        assertThat(response.columns()).hasSize(2);

        // Verify in DB
        Long count = dsl.selectCount()
                .from(DATASET)
                .where(DATASET.NAME.eq("Test Dataset"))
                .fetchOne(0, Long.class);
        assertThat(count).isEqualTo(1);

        Long columnCount = dsl.selectCount()
                .from(DATASET_COLUMN)
                .where(DATASET_COLUMN.DATASET_ID.eq(response.id()))
                .fetchOne(0, Long.class);
        assertThat(columnCount).isEqualTo(2);

        // Verify physical table exists
        Long tableExists = dsl.selectCount()
                .from("information_schema.tables")
                .where("table_schema = 'data' AND table_name = 'test_dataset'")
                .fetchOne(0, Long.class);
        assertThat(tableExists).isEqualTo(1);
    }

    @Test
    void createDataset_duplicateName_throwsException() {
        // Given
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)
        );

        CreateDatasetRequest request1 = new CreateDatasetRequest(
                "Duplicate",
                "table1",
                null,
                null,
                "SOURCE",
                columns
        );

        datasetService.createDataset(request1, testUserId);

        CreateDatasetRequest request2 = new CreateDatasetRequest(
                "Duplicate",
                "table2",
                null,
                null,
                "SOURCE",
                columns
        );

        // When/Then
        assertThatThrownBy(() -> datasetService.createDataset(request2, testUserId))
                .isInstanceOf(DuplicateDatasetNameException.class);
    }

    @Test
    void getDatasets_withFilters_returnsFilteredList() {
        // Given
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)
        );

        datasetService.createDataset(new CreateDatasetRequest(
                "Dataset A",
                "dataset_a",
                null,
                testCategoryId,
                "SOURCE",
                columns
        ), testUserId);

        datasetService.createDataset(new CreateDatasetRequest(
                "Dataset B",
                "dataset_b",
                null,
                null,
                "DERIVED",
                columns
        ), testUserId);

        // When
        PageResponse<DatasetResponse> result = datasetService.getDatasets(testCategoryId, null, null, 0, 10);

        // Then
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).name()).isEqualTo("Dataset A");
    }

    @Test
    void updateDataset_success() {
        // Given
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "Original Name",
                "original_table",
                "Original description",
                null,
                "SOURCE",
                columns
        ), testUserId);

        UpdateDatasetRequest updateRequest = new UpdateDatasetRequest(
                "Updated Name",
                "Updated description",
                testCategoryId
        );

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
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "Test Dataset",
                "test_table",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);

        AddColumnRequest addColumnRequest = new AddColumnRequest(
                "new_column",
                "New Column",
                "INTEGER",
                null,
                true,
                false,
                "New column description"
        );

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
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col_a", "Column A", "TEXT", null, true, false, null),
                new DatasetColumnRequest("col_b", "Column B", "INTEGER", null, true, false, null),
                new DatasetColumnRequest("col_c", "Column C", "BOOLEAN", null, true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "Reorder Test",
                "reorder_test",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);

        // Original order: col_a(0), col_b(1), col_c(2)
        List<DatasetColumnResponse> originalColumns = dataset.columns();
        assertThat(originalColumns).hasSize(3);
        assertThat(originalColumns.get(0).columnName()).isEqualTo("col_a");
        assertThat(originalColumns.get(1).columnName()).isEqualTo("col_b");
        assertThat(originalColumns.get(2).columnName()).isEqualTo("col_c");

        // Reorder to: col_c, col_a, col_b
        List<Long> reorderedIds = List.of(
                originalColumns.get(2).id(),
                originalColumns.get(0).id(),
                originalColumns.get(1).id()
        );

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
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col_a", "Column A", "TEXT", null, true, false, null),
                new DatasetColumnRequest("col_b", "Column B", "INTEGER", null, true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "Reorder Fail Test",
                "reorder_fail_test",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);

        List<Long> incompleteIds = List.of(dataset.columns().get(0).id());

        // When/Then
        assertThatThrownBy(() -> datasetService.reorderColumns(dataset.id(), new ReorderColumnsRequest(incompleteIds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Column IDs must match exactly");
    }

    @Test
    void reorderColumns_duplicateIds_throwsException() {
        // Given
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col_a", "Column A", "TEXT", null, true, false, null),
                new DatasetColumnRequest("col_b", "Column B", "INTEGER", null, true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "Reorder Dup Test",
                "reorder_dup_test",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);

        Long firstColId = dataset.columns().get(0).id();
        List<Long> duplicateIds = List.of(firstColId, firstColId);

        // When/Then
        assertThatThrownBy(() -> datasetService.reorderColumns(dataset.id(), new ReorderColumnsRequest(duplicateIds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate column IDs");
    }

    @Test
    void reorderColumns_nonExistentDataset_throwsException() {
        // When/Then
        assertThatThrownBy(() -> datasetService.reorderColumns(999999L, new ReorderColumnsRequest(List.of(1L, 2L))))
                .isInstanceOf(DatasetNotFoundException.class);
    }

    @Test
    void deleteDataset_removesDataAndTable() {
        // Given
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "To Delete",
                "to_delete",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);

        Long datasetId = dataset.id();

        // When
        datasetService.deleteDataset(datasetId);

        // Then
        assertThatThrownBy(() -> datasetService.getDatasetById(datasetId))
                .isInstanceOf(DatasetNotFoundException.class);

        // Verify columns deleted
        Long columnCount = dsl.selectCount()
                .from(DATASET_COLUMN)
                .where(DATASET_COLUMN.DATASET_ID.eq(datasetId))
                .fetchOne(0, Long.class);
        assertThat(columnCount).isEqualTo(0);

        // Verify physical table dropped
        Long tableExists = dsl.selectCount()
                .from("information_schema.tables")
                .where("table_schema = 'data' AND table_name = 'to_delete'")
                .fetchOne(0, Long.class);
        assertThat(tableExists).isEqualTo(0);
    }
}
