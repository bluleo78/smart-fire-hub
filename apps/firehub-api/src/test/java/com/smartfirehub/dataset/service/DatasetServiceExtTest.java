package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetService 추가 통합 테스트. updateStatus, getDatasets(확장 시그니처), updateColumn, createDataset 엣지 케이스
 * 등 미커버 분기 포함.
 */
@Transactional
class DatasetServiceExtTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetFavoriteService datasetFavoriteService;
  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long testCategoryId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "ext_testuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Ext Test User")
            .set(USER.EMAIL, "ext_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    testCategoryId =
        dsl.insertInto(DATASET_CATEGORY)
            .set(DATASET_CATEGORY.NAME, "Ext Test Category")
            .set(DATASET_CATEGORY.DESCRIPTION, "Ext Test Description")
            .returning(DATASET_CATEGORY.ID)
            .fetchOne()
            .getId();
  }

  // =========================================================================
  // 헬퍼
  // =========================================================================

  private DatasetDetailResponse createSimpleDataset(String name, String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    return datasetService.createDataset(
        new CreateDatasetRequest(name, tableName, null, null, "SOURCE", columns, null), testUserId);
  }

  private DatasetDetailResponse createDatasetWithColumns(
      String name, String tableName, List<DatasetColumnRequest> columns) {
    return datasetService.createDataset(
        new CreateDatasetRequest(name, tableName, null, null, "SOURCE", columns, null), testUserId);
  }

  // =========================================================================
  // createDataset — 엣지 케이스
  // =========================================================================

  @Test
  void createDataset_nullablePrimaryKey_throwsException() {
    // PK 컬럼은 nullable 불가
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest(
                "id", "ID", "INTEGER", null, true /* nullable */, false, null, true /* isPK */));

    assertThatThrownBy(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "NullPK", "null_pk", null, null, "SOURCE", columns, null),
                    testUserId))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageContaining("nullable");
  }

  @Test
  void createDataset_invalidCategoryId_throwsCategoryNotFound() {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    assertThatThrownBy(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "BadCat", "bad_cat", null, 999999L, "SOURCE", columns, null),
                    testUserId))
        .isInstanceOf(CategoryNotFoundException.class);
  }

  @Test
  void createDataset_duplicateTableName_throwsException() {
    createSimpleDataset("DupTable1", "dup_table_ext");

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    assertThatThrownBy(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "DupTable2", "dup_table_ext", null, null, "SOURCE", columns, null),
                    testUserId))
        .isInstanceOf(DuplicateDatasetNameException.class);
  }

  // =========================================================================
  // getDatasets — 확장 시그니처 (userId, status, favoriteOnly)
  // =========================================================================

  @Test
  void getDatasets_withStatus_filtersCorrectly() {
    DatasetDetailResponse ds = createSimpleDataset("StatusFilter", "status_filter_ext");
    datasetService.updateStatus(ds.id(), new UpdateStatusRequest("CERTIFIED", "ok"), testUserId);

    PageResponse<DatasetResponse> certified =
        datasetService.getDatasets(null, null, null, 0, 10, testUserId, "CERTIFIED", false);
    PageResponse<DatasetResponse> deprecated =
        datasetService.getDatasets(null, null, null, 0, 10, testUserId, "DEPRECATED", false);

    List<Long> certIds = certified.content().stream().map(DatasetResponse::id).toList();
    assertThat(certIds).contains(ds.id());

    List<Long> depIds = deprecated.content().stream().map(DatasetResponse::id).toList();
    assertThat(depIds).doesNotContain(ds.id());
  }

  @Test
  void getDatasets_favoriteOnly_returnsOnlyFavorites() {
    DatasetDetailResponse fav = createSimpleDataset("FavDS", "fav_ds_ext");
    createSimpleDataset("NonFavDS", "non_fav_ds_ext");

    datasetFavoriteService.toggleFavorite(fav.id(), testUserId);

    PageResponse<DatasetResponse> result =
        datasetService.getDatasets(null, null, null, 0, 10, testUserId, null, true);

    List<Long> ids = result.content().stream().map(DatasetResponse::id).toList();
    assertThat(ids).contains(fav.id());
  }

  @Test
  void getDatasets_searchByName_returnsMatchingDatasets() {
    createSimpleDataset("SearchMeExt", "search_me_ext");
    createSimpleDataset("IgnoreMeExt", "ignore_me_ext");

    PageResponse<DatasetResponse> result =
        datasetService.getDatasets(null, null, "SearchMeExt", 0, 10, null, null, false);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("SearchMeExt");
  }

  // =========================================================================
  // updateStatus
  // =========================================================================

  @Test
  void updateStatus_certified_success() {
    DatasetDetailResponse ds = createSimpleDataset("StatusDS", "status_ds_ext");

    DatasetDetailResponse updated =
        datasetService.updateStatus(
            ds.id(), new UpdateStatusRequest("CERTIFIED", "Verified"), testUserId);

    assertThat(updated.status()).isEqualTo("CERTIFIED");
    assertThat(updated.statusNote()).isEqualTo("Verified");
  }

  @Test
  void updateStatus_deprecated_success() {
    DatasetDetailResponse ds = createSimpleDataset("DeprecatedDS", "deprecated_ds_ext");

    DatasetDetailResponse updated =
        datasetService.updateStatus(
            ds.id(), new UpdateStatusRequest("DEPRECATED", "Old data"), testUserId);

    assertThat(updated.status()).isEqualTo("DEPRECATED");
  }

  @Test
  void updateStatus_none_resetsStatus() {
    DatasetDetailResponse ds = createSimpleDataset("ResetStatus", "reset_status_ext");
    datasetService.updateStatus(ds.id(), new UpdateStatusRequest("CERTIFIED", "cert"), testUserId);

    DatasetDetailResponse reset =
        datasetService.updateStatus(ds.id(), new UpdateStatusRequest("NONE", null), testUserId);

    assertThat(reset.status()).isEqualTo("NONE");
  }

  @Test
  void updateStatus_invalidStatus_throwsException() {
    DatasetDetailResponse ds = createSimpleDataset("InvalidStatus", "invalid_status_ext");

    assertThatThrownBy(
            () ->
                datasetService.updateStatus(
                    ds.id(), new UpdateStatusRequest("INVALID", null), testUserId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateStatus_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(
            () ->
                datasetService.updateStatus(
                    999999L, new UpdateStatusRequest("CERTIFIED", null), testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // updateColumn — 엣지 케이스 (displayName/description 변경 = 항상 허용)
  // =========================================================================

  @Test
  void updateColumn_displayName_success() {
    DatasetDetailResponse ds =
        createDatasetWithColumns(
            "UpdateColDisplay",
            "update_col_display_ext",
            List.of(
                new DatasetColumnRequest(
                    "col1", "Original Name", "TEXT", null, true, false, null)));

    Long colId = ds.columns().get(0).id();

    datasetService.updateColumn(
        ds.id(),
        colId,
        new UpdateColumnRequest(null, "New Display Name", null, null, null, false, null, null));

    DatasetDetailResponse updated = datasetService.getDatasetById(ds.id());
    assertThat(updated.columns().get(0).displayName()).isEqualTo("New Display Name");
  }

  @Test
  void updateColumn_addIndex_success() {
    DatasetDetailResponse ds =
        createDatasetWithColumns(
            "UpdateColIndex",
            "update_col_index_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));

    Long colId = ds.columns().get(0).id();

    // 데이터 없을 때 인덱스 추가 성공
    datasetService.updateColumn(
        ds.id(),
        colId,
        new UpdateColumnRequest(null, null, null, null, null, true /* isIndexed */, null, null));

    DatasetDetailResponse updated = datasetService.getDatasetById(ds.id());
    assertThat(updated.columns().get(0).isIndexed()).isTrue();
  }

  @Test
  void updateColumn_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    999999L,
                    1L,
                    new UpdateColumnRequest(null, "Name", null, null, null, false, null, null)))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  @Test
  void updateColumn_invalidDataType_throwsException() {
    DatasetDetailResponse ds =
        createDatasetWithColumns(
            "BadTypeDS",
            "bad_type_ds_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));

    Long colId = ds.columns().get(0).id();

    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    ds.id(),
                    colId,
                    new UpdateColumnRequest(
                        null, null, "INVALID_TYPE", null, null, null, null, null)))
        .isInstanceOf(ColumnModificationException.class);
  }

  // =========================================================================
  // getReferences
  // =========================================================================

  @Test
  void getReferences_existingDataset_returnsResponse() {
    DatasetDetailResponse ds = createSimpleDataset("RefTestDS", "ref_test_ds_ext");

    DatasetReferencesResponse refs = datasetService.getReferences(ds.id());

    assertThat(refs).isNotNull();
    assertThat(refs.datasetId()).isEqualTo(ds.id());
    assertThat(refs.pipelines()).isNotNull();
    assertThat(refs.dashboards()).isNotNull();
  }

  @Test
  void getReferences_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(() -> datasetService.getReferences(999999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // updateDataset — 잘못된 categoryId
  // =========================================================================

  @Test
  void updateDataset_invalidCategoryId_throwsCategoryNotFound() {
    DatasetDetailResponse ds = createSimpleDataset("UpdateCatBad", "update_cat_bad_ext");

    assertThatThrownBy(
            () ->
                datasetService.updateDataset(
                    ds.id(), new UpdateDatasetRequest("NewName", null, 999999L), testUserId))
        .isInstanceOf(CategoryNotFoundException.class);
  }

  @Test
  void updateDataset_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(
            () ->
                datasetService.updateDataset(
                    999999L, new UpdateDatasetRequest("X", null, null), testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // addColumn — 데이터 있을 때 제약 검증
  // =========================================================================

  @Test
  void addColumn_nonNullableToDatasetWithData_throwsException() {
    // 데이터가 있는 데이터셋에 NOT NULL 컬럼 추가 시 예외
    DatasetDetailResponse ds = createSimpleDataset("AddColNonNull", "add_col_non_null_ext");
    // 데이터 삽입
    dsl.execute("INSERT INTO data.add_col_non_null_ext (col1) VALUES ('x')");

    assertThatThrownBy(
            () ->
                datasetService.addColumn(
                    ds.id(),
                    new AddColumnRequest(
                        "new_col", "New Col", "TEXT", null, false /* not nullable */, false, null)))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageContaining("non-nullable");
  }

  @Test
  void addColumn_primaryKeyToDatasetWithData_throwsException() {
    // 데이터가 있는 데이터셋에 PK 컬럼 추가 시 예외
    DatasetDetailResponse ds = createSimpleDataset("AddColPKData", "add_col_pk_data_ext");
    dsl.execute("INSERT INTO data.add_col_pk_data_ext (col1) VALUES ('x')");

    assertThatThrownBy(
            () ->
                datasetService.addColumn(
                    ds.id(),
                    new AddColumnRequest(
                        "pk_col", "PK Col", "TEXT", null, true, false, null, true /* isPK */)))
        .isInstanceOf(ColumnModificationException.class);
  }

  @Test
  void addColumn_nullablePrimaryKey_throwsException() {
    // nullable PK 컬럼 추가 시 예외 (데이터 없어도)
    DatasetDetailResponse ds = createSimpleDataset("AddColNullPK", "add_col_null_pk_ext");

    assertThatThrownBy(
            () ->
                datasetService.addColumn(
                    ds.id(),
                    new AddColumnRequest(
                        "pk_col",
                        "PK Col",
                        "TEXT",
                        null,
                        true /* nullable */,
                        false,
                        null,
                        true /* isPK */)))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageContaining("nullable");
  }

  @Test
  void addColumn_withPrimaryKey_emptyDataset_success() {
    // 데이터 없는 경우 PK not-null 컬럼 추가 성공
    DatasetDetailResponse ds = createSimpleDataset("AddColPKOk", "add_col_pk_ok_ext");

    DatasetColumnResponse col =
        datasetService.addColumn(
            ds.id(),
            new AddColumnRequest(
                "pk_col",
                "PK Col",
                "TEXT",
                null,
                false /* not nullable */,
                false,
                null,
                true /* isPK */));

    assertThat(col).isNotNull();
    assertThat(col.isPrimaryKey()).isTrue();
  }

  // =========================================================================
  // updateColumn — 데이터 있을 때 rename/type-change 제약
  // =========================================================================

  @Test
  void updateColumn_renameWithData_throwsException() {
    DatasetDetailResponse ds =
        createDatasetWithColumns(
            "RenameWithData",
            "rename_with_data_ext",
            List.of(new DatasetColumnRequest("old_name", "Old", "TEXT", null, true, false, null)));
    dsl.execute("INSERT INTO data.rename_with_data_ext (old_name) VALUES ('x')");
    Long colId = ds.columns().get(0).id();

    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    ds.id(),
                    colId,
                    new UpdateColumnRequest("new_name", null, null, null, null, false, null, null)))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageContaining("rename");
  }

  @Test
  void updateColumn_changeTypeWithData_throwsException() {
    DatasetDetailResponse ds =
        createDatasetWithColumns(
            "TypeChangeWithData",
            "type_change_with_data_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));
    dsl.execute("INSERT INTO data.type_change_with_data_ext (col1) VALUES ('x')");
    Long colId = ds.columns().get(0).id();

    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    ds.id(),
                    colId,
                    new UpdateColumnRequest(null, null, "INTEGER", null, null, false, null, null)))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageContaining("data type");
  }

  @Test
  void updateColumn_changeNullableConstraintWithData_throwsException() {
    DatasetDetailResponse ds =
        createDatasetWithColumns(
            "NullableChangeWithData",
            "nullable_change_with_data_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));
    dsl.execute("INSERT INTO data.nullable_change_with_data_ext (col1) VALUES ('x')");
    Long colId = ds.columns().get(0).id();

    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    ds.id(),
                    colId,
                    new UpdateColumnRequest(
                        null, null, null, null, false /* change to NOT NULL */, false, null, null)))
        .isInstanceOf(ColumnModificationException.class)
        .hasMessageContaining("nullable");
  }

  @Test
  void updateColumn_columnNotBelongingToDataset_throwsException() {
    DatasetDetailResponse ds1 =
        createDatasetWithColumns(
            "DS1ForCol",
            "ds1_for_col_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));
    DatasetDetailResponse ds2 =
        createDatasetWithColumns(
            "DS2ForCol",
            "ds2_for_col_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));

    Long col1Id = ds1.columns().get(0).id();

    // ds2의 ID로 ds1의 컬럼을 업데이트 시도
    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    ds2.id(),
                    col1Id,
                    new UpdateColumnRequest(null, "New Name", null, null, null, false, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not belong");
  }

  // =========================================================================
  // deleteColumn — 컬럼이 다른 데이터셋 소속인 경우
  // =========================================================================

  @Test
  void deleteColumn_columnNotBelongingToDataset_throwsException() {
    DatasetDetailResponse ds1 =
        createDatasetWithColumns(
            "DelColDS1",
            "del_col_ds1_ext",
            List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null),
                new DatasetColumnRequest("col2", "Col2", "TEXT", null, true, false, null)));
    DatasetDetailResponse ds2 =
        createDatasetWithColumns(
            "DelColDS2",
            "del_col_ds2_ext",
            List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)));

    Long col1Id = ds1.columns().get(0).id();

    assertThatThrownBy(() -> datasetService.deleteColumn(ds2.id(), col1Id))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not belong");
  }
}
