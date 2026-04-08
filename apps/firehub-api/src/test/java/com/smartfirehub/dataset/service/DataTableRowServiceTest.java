package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DataTableRowService 통합 테스트.
 *
 * <p>data 스키마의 동적 테이블에 대한 행 CRUD 로직을 검증한다:
 * - insertRow / insertBatch: 단건/배치 삽입
 * - queryData: 조회, 검색, 정렬, 페이징
 * - countRows: 전체/검색 카운트
 * - getRow: 단건 조회, 없는 행 예외
 * - updateRow: 수정, 없는 행 예외
 * - deleteRows: 행 삭제
 * - truncateTable: 전체 행 삭제
 * - checkDataUniqueness / findDuplicateRows: 중복 검사
 * - upsertBatch: INSERT/UPDATE 구분 반환 (xmax 트릭)
 */
@Transactional
class DataTableRowServiceTest extends IntegrationTestBase {

  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /** 테스트용 사용자 ID */
  private Long testUserId;

  // =========================================================================
  // Setup
  // =========================================================================

  @BeforeEach
  void setUp() {
    // 테스트 사용자 생성 — 데이터셋 생성에 createdBy로 필요
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "row_service_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Row Service User")
            .set(USER.EMAIL, "row_service@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  // =========================================================================
  // Helper
  // =========================================================================

  /**
   * TEXT(name) + INTEGER(value) 컬럼을 가진 데이터셋 및 data 스키마 테이블을 생성한다.
   *
   * @param tableName 물리 테이블명 (test 내 유일해야 함)
   */
  private DatasetDetailResponse createSimpleDataset(String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));
    return datasetService.createDataset(
        new CreateDatasetRequest(tableName, tableName, null, null, "SOURCE", columns, null),
        testUserId);
  }

  // =========================================================================
  // insertRow — 단건 삽입
  // =========================================================================

  /**
   * 정상: insertRow가 생성된 행의 ID(양수)를 반환해야 한다.
   * data 스키마 테이블에 실제로 행이 존재함을 countRows로 교차 검증한다.
   */
  @Test
  void insertRow_success_returnsGeneratedId() {
    DatasetDetailResponse dataset = createSimpleDataset("insert_row_test");
    String tableName = dataset.tableName();

    Long id = dataTableRowService.insertRow(
        tableName,
        List.of("name", "value"),
        Map.of("name", "Alice", "value", 42));

    assertThat(id).isNotNull().isPositive();
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(1L);
  }

  /**
   * 정상: 여러 행을 삽입하면 각각 고유한 ID를 가져야 한다.
   */
  @Test
  void insertRow_multipleRows_uniqueIds() {
    DatasetDetailResponse dataset = createSimpleDataset("insert_multi_test");
    String tableName = dataset.tableName();

    Long id1 = dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "Alice", "value", 1));
    Long id2 = dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "Bob", "value", 2));

    assertThat(id1).isNotEqualTo(id2);
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(2L);
  }

  // =========================================================================
  // insertBatch — 배치 삽입
  // =========================================================================

  /**
   * 정상: insertBatch로 여러 행을 삽입하면 countRows가 정확한 수를 반환해야 한다.
   */
  @Test
  void insertBatch_success_rowCountMatches() {
    DatasetDetailResponse dataset = createSimpleDataset("insert_batch_test");
    String tableName = dataset.tableName();

    List<Map<String, Object>> rows = List.of(
        Map.of("name", "Alice", "value", 10),
        Map.of("name", "Bob", "value", 20),
        Map.of("name", "Charlie", "value", 30));

    dataTableRowService.insertBatch(tableName, List.of("name", "value"), rows);

    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(3L);
  }

  /**
   * 엣지 케이스: 빈 rows 목록으로 insertBatch 호출 시 예외 없이 종료되고 행이 삽입되지 않아야 한다.
   */
  @Test
  void insertBatch_emptyRows_noException() {
    DatasetDetailResponse dataset = createSimpleDataset("insert_batch_empty_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(tableName, List.of("name", "value"), List.of());

    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(0L);
  }

  // =========================================================================
  // queryData — 조회, 검색, 정렬, 페이징
  // =========================================================================

  /**
   * 정상: 삽입된 행을 queryData로 전체 조회하면 모든 행이 반환되어야 한다.
   */
  @Test
  void queryData_basicQuery_returnsAllRows() {
    DatasetDetailResponse dataset = createSimpleDataset("query_data_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "Alice", "value", 10),
            Map.of("name", "Bob", "value", 20)));

    List<Map<String, Object>> rows = dataTableRowService.queryData(
        tableName, List.of("name", "value"), null, 0, 10);

    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(r -> r.get("name")).containsExactlyInAnyOrder("Alice", "Bob");
  }

  /**
   * 정상: search 파라미터로 ILIKE 검색하면 일치하는 행만 반환되어야 한다.
   */
  @Test
  void queryData_withSearch_filtersRows() {
    DatasetDetailResponse dataset = createSimpleDataset("query_search_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "Alice", "value", 10),
            Map.of("name", "Bob", "value", 20),
            Map.of("name", "Alexander", "value", 30)));

    List<Map<String, Object>> rows = dataTableRowService.queryData(
        tableName, List.of("name", "value"), "al", 0, 10);

    // "al" ILIKE 검색 — Alice, Alexander 모두 매칭
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(r -> r.get("name")).containsExactlyInAnyOrder("Alice", "Alexander");
  }

  /**
   * 정상: page/size 파라미터로 페이징 시 정확한 페이지만 반환되어야 한다.
   */
  @Test
  void queryData_pagination_returnsCorrectPage() {
    DatasetDetailResponse dataset = createSimpleDataset("query_page_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "A", "value", 1),
            Map.of("name", "B", "value", 2),
            Map.of("name", "C", "value", 3),
            Map.of("name", "D", "value", 4)));

    // page=0, size=2 → 첫 2행
    List<Map<String, Object>> page0 = dataTableRowService.queryData(
        tableName, List.of("name", "value"), null, 0, 2);
    // page=1, size=2 → 다음 2행
    List<Map<String, Object>> page1 = dataTableRowService.queryData(
        tableName, List.of("name", "value"), null, 1, 2);

    assertThat(page0).hasSize(2);
    assertThat(page1).hasSize(2);
    // 두 페이지의 id가 겹치지 않아야 함
    assertThat(page0.stream().map(r -> r.get("id")).toList())
        .doesNotContainAnyElementsOf(page1.stream().map(r -> r.get("id")).toList());
  }

  /**
   * 정상: sortBy + sortDir 지정 시 결과가 정렬 순서를 따라야 한다.
   */
  @Test
  void queryData_withSortDesc_returnsSortedRows() {
    DatasetDetailResponse dataset = createSimpleDataset("query_sort_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "C", "value", 30),
            Map.of("name", "A", "value", 10),
            Map.of("name", "B", "value", 20)));

    List<Map<String, Object>> rows = dataTableRowService.queryData(
        tableName, List.of("name", "value"), null, 0, 10, "value", "DESC");

    // value 내림차순: 30, 20, 10 — DB에서 BIGINT로 반환되므로 Long으로 비교
    List<Object> values = rows.stream().map(r -> r.get("value")).toList();
    assertThat(values).extracting(v -> ((Number) v).longValue())
        .containsExactly(30L, 20L, 10L);
  }

  // =========================================================================
  // countRows — 전체/검색 카운트
  // =========================================================================

  /**
   * 정상: 빈 테이블의 countRows는 0을 반환해야 한다.
   */
  @Test
  void countRows_emptyTable_returnsZero() {
    DatasetDetailResponse dataset = createSimpleDataset("count_empty_test");
    String tableName = dataset.tableName();

    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(0L);
  }

  /**
   * 정상: 행 삽입 후 countRows는 삽입된 행 수를 반환해야 한다.
   */
  @Test
  void countRows_afterInsert_returnsCorrectCount() {
    DatasetDetailResponse dataset = createSimpleDataset("count_after_insert_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "A", "value", 1),
            Map.of("name", "B", "value", 2),
            Map.of("name", "C", "value", 3)));

    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(3L);
  }

  /**
   * 정상: search 파라미터 적용 시 일치하는 행 수만 반환되어야 한다.
   */
  @Test
  void countRows_withSearch_returnsFilteredCount() {
    DatasetDetailResponse dataset = createSimpleDataset("count_search_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "Apple", "value", 1),
            Map.of("name", "Banana", "value", 2),
            Map.of("name", "Apricot", "value", 3)));

    long count = dataTableRowService.countRows(tableName, List.of("name", "value"), "ap");

    // "ap" ILIKE: Apple, Apricot 2개
    assertThat(count).isEqualTo(2L);
  }

  // =========================================================================
  // getRow — 단건 조회
  // =========================================================================

  /**
   * 정상: insertRow 후 반환된 ID로 getRow 호출 시 삽입한 값을 반환해야 한다.
   */
  @Test
  void getRow_existingRow_returnsCorrectData() {
    DatasetDetailResponse dataset = createSimpleDataset("get_row_test");
    String tableName = dataset.tableName();

    Long id = dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "TestUser", "value", 99));

    Map<String, Object> row = dataTableRowService.getRow(tableName, List.of("name", "value"), id);

    assertThat(row.get("name")).isEqualTo("TestUser");
    assertThat(row.get("id")).isEqualTo(id);
  }

  /**
   * 예외: 존재하지 않는 rowId로 getRow 호출 시 RowNotFoundException이 발생해야 한다.
   */
  @Test
  void getRow_nonExistentRow_throwsRowNotFoundException() {
    DatasetDetailResponse dataset = createSimpleDataset("get_row_not_found_test");
    String tableName = dataset.tableName();

    assertThatThrownBy(
            () -> dataTableRowService.getRow(tableName, List.of("name", "value"), 999999L))
        .isInstanceOf(RowNotFoundException.class);
  }

  // =========================================================================
  // updateRow — 행 수정
  // =========================================================================

  /**
   * 정상: updateRow 후 getRow로 다시 조회하면 수정된 값을 반환해야 한다.
   */
  @Test
  void updateRow_success_dataUpdated() {
    DatasetDetailResponse dataset = createSimpleDataset("update_row_test");
    String tableName = dataset.tableName();

    Long id = dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "Original", "value", 1));

    dataTableRowService.updateRow(
        tableName, id, List.of("name", "value"), Map.of("name", "Updated", "value", 100));

    Map<String, Object> row = dataTableRowService.getRow(tableName, List.of("name", "value"), id);
    assertThat(row.get("name")).isEqualTo("Updated");
  }

  /**
   * 예외: 존재하지 않는 rowId로 updateRow 호출 시 RowNotFoundException이 발생해야 한다.
   */
  @Test
  void updateRow_nonExistentRow_throwsRowNotFoundException() {
    DatasetDetailResponse dataset = createSimpleDataset("update_row_not_found_test");
    String tableName = dataset.tableName();

    assertThatThrownBy(
            () -> dataTableRowService.updateRow(
                tableName, 999999L, List.of("name", "value"), Map.of("name", "X", "value", 0)))
        .isInstanceOf(RowNotFoundException.class);
  }

  // =========================================================================
  // deleteRows — 행 삭제
  // =========================================================================

  /**
   * 정상: deleteRows 후 countRows가 0이 되어야 하고, 반환값은 삭제된 행 수와 일치해야 한다.
   */
  @Test
  void deleteRows_success_rowsRemoved() {
    DatasetDetailResponse dataset = createSimpleDataset("delete_rows_test");
    String tableName = dataset.tableName();

    Long id1 = dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "A", "value", 1));
    Long id2 = dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "B", "value", 2));

    int deleted = dataTableRowService.deleteRows(tableName, List.of(id1, id2));

    assertThat(deleted).isEqualTo(2);
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(0L);
  }

  /**
   * 엣지 케이스: 빈 ID 목록으로 deleteRows 호출 시 0을 반환하고 기존 행은 유지되어야 한다.
   */
  @Test
  void deleteRows_emptyList_returnsZero() {
    DatasetDetailResponse dataset = createSimpleDataset("delete_empty_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertRow(
        tableName, List.of("name", "value"), Map.of("name", "A", "value", 1));

    int deleted = dataTableRowService.deleteRows(tableName, List.of());

    assertThat(deleted).isEqualTo(0);
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(1L);
  }

  // =========================================================================
  // truncateTable — 전체 행 삭제
  // =========================================================================

  /**
   * 정상: truncateTable 호출 후 모든 행이 삭제되어 countRows가 0이 되어야 한다.
   */
  @Test
  void truncateTable_success_allRowsRemoved() {
    DatasetDetailResponse dataset = createSimpleDataset("truncate_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "A", "value", 1),
            Map.of("name", "B", "value", 2)));

    dataTableRowService.truncateTable(tableName);

    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(0L);
  }

  // =========================================================================
  // checkDataUniqueness / findDuplicateRows
  // =========================================================================

  /**
   * 정상: 중복 없는 데이터 삽입 후 checkDataUniqueness는 true를 반환해야 한다.
   */
  @Test
  void checkDataUniqueness_uniqueData_returnsTrue() {
    DatasetDetailResponse dataset = createSimpleDataset("uniqueness_true_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "Alice", "value", 1),
            Map.of("name", "Bob", "value", 2)));

    boolean unique = dataTableRowService.checkDataUniqueness(tableName, List.of("name"));

    assertThat(unique).isTrue();
  }

  /**
   * 정상: 중복 데이터 삽입 후 checkDataUniqueness는 false를 반환해야 한다.
   */
  @Test
  void checkDataUniqueness_duplicateData_returnsFalse() {
    DatasetDetailResponse dataset = createSimpleDataset("uniqueness_false_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "Alice", "value", 1),
            Map.of("name", "Alice", "value", 2)));  // name 중복

    boolean unique = dataTableRowService.checkDataUniqueness(tableName, List.of("name"));

    assertThat(unique).isFalse();
  }

  /**
   * 정상: findDuplicateRows는 중복 행의 중복 횟수(duplicate_count)와 함께 반환해야 한다.
   */
  @Test
  void findDuplicateRows_withDuplicates_returnsDuplicateInfo() {
    DatasetDetailResponse dataset = createSimpleDataset("find_dup_test");
    String tableName = dataset.tableName();

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "value"),
        List.of(
            Map.of("name", "Alice", "value", 1),
            Map.of("name", "Alice", "value", 2),
            Map.of("name", "Alice", "value", 3),  // name="Alice" 3번 중복
            Map.of("name", "Bob", "value", 4)));

    List<Map<String, Object>> duplicates =
        dataTableRowService.findDuplicateRows(tableName, List.of("name"), 10);

    assertThat(duplicates).hasSize(1);
    assertThat(duplicates.get(0).get("name")).isEqualTo("Alice");
    // duplicate_count는 숫자형이어야 하며 3 이상
    Object count = duplicates.get(0).get("duplicate_count");
    assertThat(((Number) count).longValue()).isEqualTo(3L);
  }

  // =========================================================================
  // upsertBatch — INSERT/UPDATE 구분
  // =========================================================================

  /**
   * 정상: 새 행 upsert 시 UpsertResult.inserted가 삽입 행 수와 일치하고 updated=0이어야 한다.
   * 이 테스트는 데이터셋에 pkColumns가 있어야 하므로 pk=true인 컬럼을 가진 데이터셋을 사용한다.
   * upsertBatch는 ON CONFLICT(pkColumns)를 사용하므로 unique index가 있어야 한다.
   * DataTableService.createTable()에서 pk 컬럼에 대해 unique index를 생성한다.
   */
  @Test
  void upsertBatch_newRows_countedAsInserted() {
    // pk=true 컬럼이 있는 데이터셋 생성 — DataTableService가 unique index를 만든다
    // DatasetColumnRequest: (columnName, displayName, dataType, maxLength, isNullable, isIndexed, description, isPrimaryKey)
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),  // isPrimaryKey=true
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null, false));
    DatasetDetailResponse dataset = datasetService.createDataset(
        new CreateDatasetRequest("upsert_new", "upsert_new", null, null, "SOURCE", columns, null),
        testUserId);
    String tableName = dataset.tableName();

    List<Map<String, Object>> rows = List.of(
        Map.of("code", "C1", "label", "Label1"),
        Map.of("code", "C2", "label", "Label2"));

    DataTableRowService.UpsertResult result =
        dataTableRowService.upsertBatch(
            tableName, List.of("code", "label"), List.of("code"), rows, null);

    assertThat(result.inserted()).isEqualTo(2);
    assertThat(result.updated()).isEqualTo(0);
  }

  /**
   * 정상: 동일 pk로 upsert 재호출 시 UpsertResult.updated가 증가하고 inserted=0이어야 한다.
   * 첫 번째 upsert에서 삽입된 행이 두 번째에서 업데이트되어야 한다.
   */
  @Test
  void upsertBatch_existingRows_countedAsUpdated() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null, false));
    DatasetDetailResponse dataset = datasetService.createDataset(
        new CreateDatasetRequest("upsert_update", "upsert_update", null, null, "SOURCE", columns,
            null),
        testUserId);
    String tableName = dataset.tableName();

    List<Map<String, Object>> rows = List.of(Map.of("code", "C1", "label", "Original"));
    dataTableRowService.upsertBatch(
        tableName, List.of("code", "label"), List.of("code"), rows, null);

    // 동일 pk로 label 변경하여 재upsert
    List<Map<String, Object>> updatedRows = List.of(Map.of("code", "C1", "label", "Updated"));
    DataTableRowService.UpsertResult result =
        dataTableRowService.upsertBatch(
            tableName, List.of("code", "label"), List.of("code"), updatedRows, null);

    assertThat(result.inserted()).isEqualTo(0);
    assertThat(result.updated()).isEqualTo(1);

    // 실제로 label이 변경되었는지 확인
    List<Map<String, Object>> queryResult =
        dataTableRowService.queryData(tableName, List.of("code", "label"), null, 0, 10);
    assertThat(queryResult).hasSize(1);
    assertThat(queryResult.get(0).get("label")).isEqualTo("Updated");
  }

  /**
   * 예외: upsertBatch에 pkColumns가 빈 목록이면 IllegalStateException이 발생해야 한다.
   */
  @Test
  void upsertBatch_emptyPkColumns_throwsIllegalStateException() {
    DatasetDetailResponse dataset = createSimpleDataset("upsert_no_pk_test");
    String tableName = dataset.tableName();

    List<Map<String, Object>> rows = List.of(Map.of("name", "A", "value", 1));

    assertThatThrownBy(
            () -> dataTableRowService.upsertBatch(
                tableName, List.of("name", "value"), List.of(), rows, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("primary key");
  }
}
