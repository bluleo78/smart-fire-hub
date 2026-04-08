package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetDataService 통합 테스트.
 *
 * <p>DatasetServiceTest에서 커버하지 않는 핵심 비즈니스 로직을 전담 검증한다: - getDatasetStats (빈 테이블 / 데이터 있음) -
 * truncateDatasetData / getRowCount - replaceDatasetData (atomic truncate+insert) - getDatasetData
 * (검색, 정렬, 페이징, includeTotalCount) - validateAndConvertRowData 타입별 분기 로직 (TEXT, VARCHAR maxLength,
 * INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, GEOMETRY) - propagateDescriptions - 존재하지 않는 데이터셋에 대한
 * 예외 처리
 */
@Transactional
class DatasetDataServiceTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DSLContext dsl;

  /** 테스트용 사용자 ID */
  private Long testUserId;

  // =========================================================================
  // Setup
  // =========================================================================

  @BeforeEach
  void setUp() {
    // 테스트 사용자 생성
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "data_service_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Data Service User")
            .set(USER.EMAIL, "data_service@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  // =========================================================================
  // Helper
  // =========================================================================

  /**
   * TEXT + INTEGER 컬럼을 가진 데이터셋을 생성하는 헬퍼.
   *
   * @param name 데이터셋 표시명
   * @param tableName 물리 테이블명
   */
  private DatasetDetailResponse createSimpleDataset(String name, String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("value", "Value", "INTEGER", null, true, false, null));
    return datasetService.createDataset(
        new CreateDatasetRequest(name, tableName, null, null, "SOURCE", columns, null), testUserId);
  }

  /**
   * 단일 컬럼(dataType 지정)을 가진 데이터셋을 생성하는 헬퍼.
   *
   * @param tableName 물리 테이블명
   * @param colName 컬럼명
   * @param dataType 데이터 타입 (TEXT, INTEGER, BOOLEAN, DATE, TIMESTAMP, GEOMETRY, DECIMAL, VARCHAR)
   * @param maxLength VARCHAR maxLength (null 가능)
   * @param isNullable nullable 여부
   */
  private DatasetDetailResponse createSingleColumnDataset(
      String tableName, String colName, String dataType, Integer maxLength, boolean isNullable) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest(
                colName, colName, dataType, maxLength, isNullable, false, null));
    return datasetService.createDataset(
        new CreateDatasetRequest(tableName, tableName, null, null, "SOURCE", columns, null),
        testUserId);
  }

  // =========================================================================
  // getDatasetStats
  // =========================================================================

  /** 정상: 데이터가 없는 테이블은 빈 통계 목록을 반환한다 */
  @Test
  void getDatasetStats_emptyTable_returnsEmptyList() {
    DatasetDetailResponse dataset = createSimpleDataset("Stats Empty", "stats_empty");

    List<ColumnStatsResponse> stats = datasetDataService.getDatasetStats(dataset.id());

    assertThat(stats).isEmpty();
  }

  /** 정상: 데이터가 있는 테이블은 컬럼별 통계를 반환한다 */
  @Test
  void getDatasetStats_withData_returnsStats() {
    DatasetDetailResponse dataset = createSimpleDataset("Stats With Data", "stats_with_data");
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Alice", "value", 100)));
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Bob", "value", 200)));

    List<ColumnStatsResponse> stats = datasetDataService.getDatasetStats(dataset.id());

    // name, value 컬럼 통계 반환
    assertThat(stats).isNotEmpty();
    assertThat(stats).anyMatch(s -> "name".equals(s.columnName()));
    assertThat(stats).anyMatch(s -> "value".equals(s.columnName()));
    // totalCount는 행 수와 일치
    stats.forEach(s -> assertThat(s.totalCount()).isEqualTo(2));
  }

  /** 예외: 존재하지 않는 데이터셋 ID로 통계 조회 시 DatasetNotFoundException */
  @Test
  void getDatasetStats_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(() -> datasetDataService.getDatasetStats(999999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // getRowCount / truncateDatasetData
  // =========================================================================

  /** 정상: 행 삽입 후 getRowCount는 정확한 행 수를 반환한다 */
  @Test
  void getRowCount_afterInsert_returnsCorrectCount() {
    DatasetDetailResponse dataset = createSimpleDataset("RowCount Test", "rowcount_test");
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "A", "value", 1)));
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "B", "value", 2)));
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "C", "value", 3)));

    RowCountResponse result = datasetDataService.getRowCount(dataset.id());

    assertThat(result.rowCount()).isEqualTo(3);
  }

  /** 정상: truncateDatasetData는 모든 행을 삭제하고 삭제된 행 수를 반환한다 */
  @Test
  void truncateDatasetData_removesAllRows_returnsDeletedCount() {
    DatasetDetailResponse dataset = createSimpleDataset("Truncate Test", "truncate_test");
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "X", "value", 10)));
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "Y", "value", 20)));

    DataDeleteResponse result = datasetDataService.truncateDatasetData(dataset.id());

    assertThat(result.deletedCount()).isEqualTo(2);
    // truncate 후 행 수는 0
    assertThat(datasetDataService.getRowCount(dataset.id()).rowCount()).isZero();
  }

  /** 정상: 빈 테이블을 truncate하면 deletedCount가 0 */
  @Test
  void truncateDatasetData_emptyTable_returnsZero() {
    DatasetDetailResponse dataset = createSimpleDataset("Truncate Empty", "truncate_empty");

    DataDeleteResponse result = datasetDataService.truncateDatasetData(dataset.id());

    assertThat(result.deletedCount()).isZero();
  }

  /** 예외: 존재하지 않는 데이터셋 truncate 시 DatasetNotFoundException */
  @Test
  void truncateDatasetData_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(() -> datasetDataService.truncateDatasetData(999999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  /** 예외: 존재하지 않는 데이터셋 getRowCount 시 DatasetNotFoundException */
  @Test
  void getRowCount_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(() -> datasetDataService.getRowCount(999999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // replaceDatasetData
  // =========================================================================

  /** 정상: replaceDatasetData는 기존 데이터를 원자적으로 교체한다 */
  @Test
  void replaceDatasetData_replacesExistingData_atomically() {
    DatasetDetailResponse dataset = createSimpleDataset("Replace Test", "replace_test");
    // 기존 데이터 2건 삽입
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "Old1", "value", 1)));
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "Old2", "value", 2)));

    // 새 데이터 3건으로 교체
    List<Map<String, Object>> newRows =
        List.of(
            Map.of("name", "New1", "value", 10),
            Map.of("name", "New2", "value", 20),
            Map.of("name", "New3", "value", 30));
    BatchRowDataResponse result =
        datasetDataService.replaceDatasetData(dataset.id(), new BatchRowDataRequest(newRows));

    assertThat(result.insertedCount()).isEqualTo(3);
    // 교체 후 총 행 수는 3 (기존 2건 삭제됨)
    assertThat(datasetDataService.getRowCount(dataset.id()).rowCount()).isEqualTo(3);
    // 교체된 데이터 내용 확인
    DataQueryResponse data = datasetDataService.getDatasetData(dataset.id(), null, 0, 10);
    List<String> names = data.rows().stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactlyInAnyOrder("New1", "New2", "New3");
  }

  /** 정상: replaceDatasetData — 빈 행 리스트로 교체하면 테이블이 비워진다 */
  @Test
  void replaceDatasetData_withEmptyRows_clearsTable() {
    DatasetDetailResponse dataset = createSimpleDataset("Replace Empty", "replace_empty");
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Existing", "value", 1)));

    BatchRowDataResponse result =
        datasetDataService.replaceDatasetData(dataset.id(), new BatchRowDataRequest(List.of()));

    assertThat(result.insertedCount()).isZero();
    assertThat(datasetDataService.getRowCount(dataset.id()).rowCount()).isZero();
  }

  /** 예외: replaceDatasetData — 잘못된 타입이 포함된 경우 IllegalArgumentException */
  @Test
  void replaceDatasetData_withInvalidType_throwsIllegalArgument() {
    DatasetDetailResponse dataset = createSimpleDataset("Replace Invalid", "replace_invalid");

    List<Map<String, Object>> rows = List.of(Map.of("name", "Valid", "value", "not_a_number"));

    assertThatThrownBy(
            () ->
                datasetDataService.replaceDatasetData(dataset.id(), new BatchRowDataRequest(rows)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 예외: 존재하지 않는 데이터셋에 replaceDatasetData 시 DatasetNotFoundException */
  @Test
  void replaceDatasetData_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(
            () ->
                datasetDataService.replaceDatasetData(999999L, new BatchRowDataRequest(List.of())))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // getDatasetData — 검색, 정렬, 페이징
  // =========================================================================

  /** 정상: 검색어 없이 전체 데이터를 페이징하여 반환한다 */
  @Test
  void getDatasetData_noFilter_returnsPaginatedData() {
    DatasetDetailResponse dataset = createSimpleDataset("Query Paging Test", "query_paging_test");
    for (int i = 1; i <= 5; i++) {
      datasetDataService.addRow(
          dataset.id(), new RowDataRequest(Map.of("name", "Item" + i, "value", i * 10)));
    }

    // 첫 번째 페이지, size=3
    DataQueryResponse page0 = datasetDataService.getDatasetData(dataset.id(), null, 0, 3);
    assertThat(page0.rows()).hasSize(3);
    assertThat(page0.totalElements()).isEqualTo(5);
    assertThat(page0.totalPages()).isEqualTo(2);

    // 두 번째 페이지, size=3
    DataQueryResponse page1 = datasetDataService.getDatasetData(dataset.id(), null, 1, 3);
    assertThat(page1.rows()).hasSize(2);
  }

  /** 정상: 검색어로 필터링하면 일치하는 행만 반환한다 */
  @Test
  void getDatasetData_withSearch_returnsMatchingRows() {
    DatasetDetailResponse dataset = createSimpleDataset("Search Filter Test", "search_filter_test");
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Apple", "value", 1)));
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Banana", "value", 2)));
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Apricot", "value", 3)));

    DataQueryResponse result = datasetDataService.getDatasetData(dataset.id(), "Ap", 0, 10);

    // "Apple"과 "Apricot" 2건만 반환
    assertThat(result.rows()).hasSize(2);
    List<String> names = result.rows().stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactlyInAnyOrder("Apple", "Apricot");
  }

  /** 정상: sortBy + sortDir로 정렬 결과를 검증한다 */
  @Test
  void getDatasetData_withSort_returnsSortedData() {
    DatasetDetailResponse dataset = createSimpleDataset("Sort Test", "sort_test");
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Charlie", "value", 30)));
    datasetDataService.addRow(
        dataset.id(), new RowDataRequest(Map.of("name", "Alice", "value", 10)));
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "Bob", "value", 20)));

    // name ASC 정렬
    DataQueryResponse ascResult =
        datasetDataService.getDatasetData(dataset.id(), null, 0, 10, "name", "ASC", true);
    List<String> ascNames = ascResult.rows().stream().map(r -> (String) r.get("name")).toList();
    assertThat(ascNames).containsExactly("Alice", "Bob", "Charlie");

    // name DESC 정렬
    DataQueryResponse descResult =
        datasetDataService.getDatasetData(dataset.id(), null, 0, 10, "name", "DESC", true);
    List<String> descNames = descResult.rows().stream().map(r -> (String) r.get("name")).toList();
    assertThat(descNames).containsExactly("Charlie", "Bob", "Alice");
  }

  /** 예외: 존재하지 않는 컬럼명으로 정렬 시 IllegalArgumentException */
  @Test
  void getDatasetData_invalidSortColumn_throwsIllegalArgument() {
    DatasetDetailResponse dataset = createSimpleDataset("Invalid Sort", "invalid_sort");

    assertThatThrownBy(
            () ->
                datasetDataService.getDatasetData(
                    dataset.id(), null, 0, 10, "non_existent_col", "ASC", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("정렬할 수 없는 컬럼");
  }

  /** 정상: includeTotalCount=false 이면 totalElements와 totalPages가 -1 */
  @Test
  void getDatasetData_withoutTotalCount_returnsMinus1() {
    DatasetDetailResponse dataset = createSimpleDataset("No Count Test", "no_count_test");
    datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("name", "X", "value", 1)));

    DataQueryResponse result =
        datasetDataService.getDatasetData(dataset.id(), null, 0, 10, null, "ASC", false);

    assertThat(result.totalElements()).isEqualTo(-1);
    assertThat(result.totalPages()).isEqualTo(-1);
    // 행 데이터는 정상 반환
    assertThat(result.rows()).hasSize(1);
  }

  /** 예외: 존재하지 않는 데이터셋 getDatasetData 시 DatasetNotFoundException */
  @Test
  void getDatasetData_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(() -> datasetDataService.getDatasetData(999999L, null, 0, 10))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  // =========================================================================
  // validateAndConvertRowData — 타입별 분기 로직
  // =========================================================================

  /** 정상: TEXT 타입 문자열 값 저장 성공 */
  @Test
  void addRow_textType_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_text", "col", "TEXT", null, true);

    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("col", "hello")));

    assertThat(row.data().get("col")).isEqualTo("hello");
  }

  /** 예외: TEXT 타입에 숫자를 넣으면 IllegalArgumentException */
  @Test
  void addRow_textType_withNonString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_text_fail", "col", "TEXT", null, true);

    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("col", 123))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected string");
  }

  /** 정상: VARCHAR(10) 최대 길이 이내 값 저장 성공 */
  @Test
  void addRow_varcharType_withinMaxLength_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_varchar_ok", "col", "VARCHAR", 10, true);

    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("col", "hello")));

    assertThat(row.data().get("col")).isEqualTo("hello");
  }

  /** 예외: VARCHAR(5) 최대 길이 초과 시 IllegalArgumentException */
  @Test
  void addRow_varcharType_exceedsMaxLength_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_varchar_fail", "col", "VARCHAR", 5, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("col", "toolongvalue"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max length");
  }

  /** 정상: INTEGER 타입 숫자 값 저장 성공 */
  @Test
  void addRow_integerType_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_integer", "num", "INTEGER", null, true);

    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("num", 42)));

    // Long으로 저장되므로 숫자 타입 확인
    assertThat(((Number) row.data().get("num")).longValue()).isEqualTo(42L);
  }

  /** 예외: INTEGER 타입에 문자열을 넣으면 IllegalArgumentException */
  @Test
  void addRow_integerType_withString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_integer_fail", "num", "INTEGER", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("num", "not_a_number"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected numeric");
  }

  /** 정상: DECIMAL 타입 숫자 값 저장 성공 */
  @Test
  void addRow_decimalType_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_decimal", "price", "DECIMAL", null, true);

    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("price", 19.99)));

    assertThat(row.data().get("price")).isNotNull();
  }

  /** 예외: DECIMAL 타입에 문자열을 넣으면 IllegalArgumentException */
  @Test
  void addRow_decimalType_withString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_decimal_fail", "price", "DECIMAL", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("price", "cheap"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected numeric");
  }

  /** 정상: BOOLEAN 타입 true/false 저장 성공 */
  @Test
  void addRow_booleanType_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_boolean", "active", "BOOLEAN", null, true);

    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("active", true)));

    assertThat(row.data().get("active")).isEqualTo(true);
  }

  /** 예외: BOOLEAN 타입에 문자열을 넣으면 IllegalArgumentException */
  @Test
  void addRow_booleanType_withString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_boolean_fail", "active", "BOOLEAN", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("active", "yes"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected boolean");
  }

  /** 정상: DATE 타입 "yyyy-MM-dd" 형식 문자열 저장 성공 */
  @Test
  void addRow_dateType_validFormat_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_date", "birthday", "DATE", null, true);

    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("birthday", "2024-01-15")));

    assertThat(row.data().get("birthday")).isNotNull();
  }

  /** 예외: DATE 타입에 잘못된 형식을 넣으면 IllegalArgumentException */
  @Test
  void addRow_dateType_invalidFormat_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_date_fail", "birthday", "DATE", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("birthday", "15/01/2024"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("yyyy-MM-dd");
  }

  /** 예외: DATE 타입에 문자열이 아닌 값을 넣으면 IllegalArgumentException */
  @Test
  void addRow_dateType_withNonString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_date_non_str", "birthday", "DATE", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("birthday", 20240115))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("date string");
  }

  /** 정상: TIMESTAMP 타입 "yyyy-MM-ddTHH:mm:ss" 형식 문자열 저장 성공 */
  @Test
  void addRow_timestampType_validFormat_success() {
    // 주의: "created_at"은 DataTableService가 시스템 컬럼으로 자동 추가하므로 다른 이름 사용
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_timestamp", "event_time", "TIMESTAMP", null, true);

    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("event_time", "2024-01-15T10:30:00")));

    assertThat(row.data().get("event_time")).isNotNull();
  }

  /** 예외: TIMESTAMP 타입에 잘못된 형식을 넣으면 IllegalArgumentException */
  @Test
  void addRow_timestampType_invalidFormat_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_timestamp_fail", "ts", "TIMESTAMP", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("ts", "2024-01-15 10:30:00"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp format");
  }

  /** 예외: TIMESTAMP 타입에 문자열이 아닌 값을 넣으면 IllegalArgumentException */
  @Test
  void addRow_timestampType_withNonString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_timestamp_non_str", "ts", "TIMESTAMP", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("ts", 1234567890L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp string");
  }

  /** 정상: GEOMETRY 타입 — 유효한 Point GeoJSON 저장 성공 */
  @Test
  void addRow_geometryType_validPoint_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_point", "geom", "GEOMETRY", null, true);

    String pointGeoJson = "{\"type\":\"Point\",\"coordinates\":[127.0,37.5]}";
    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("geom", pointGeoJson)));

    assertThat(row.data().get("geom")).isNotNull();
  }

  /** 정상: GEOMETRY 타입 — 유효한 Polygon GeoJSON 저장 성공 */
  @Test
  void addRow_geometryType_validPolygon_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_poly", "geom", "GEOMETRY", null, true);

    String polygonGeoJson =
        "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("geom", polygonGeoJson)));

    assertThat(row.data().get("geom")).isNotNull();
  }

  /** 예외: GEOMETRY 타입 — type 필드 없는 JSON은 IllegalArgumentException */
  @Test
  void addRow_geometryType_missingTypeField_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_no_type", "geom", "GEOMETRY", null, true);

    String invalidGeoJson = "{\"coordinates\":[127.0,37.5]}";
    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("geom", invalidGeoJson))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type");
  }

  /** 예외: GEOMETRY 타입 — 지원하지 않는 GeoJSON type이면 IllegalArgumentException */
  @Test
  void addRow_geometryType_unsupportedType_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_bad_type", "geom", "GEOMETRY", null, true);

    String invalidGeoJson = "{\"type\":\"Feature\",\"coordinates\":[127.0,37.5]}";
    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("geom", invalidGeoJson))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported GeoJSON type");
  }

  /** 예외: GEOMETRY 타입 — coordinates 필드 없으면 IllegalArgumentException */
  @Test
  void addRow_geometryType_missingCoordinates_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_no_coords", "geom", "GEOMETRY", null, true);

    String invalidGeoJson = "{\"type\":\"Point\"}";
    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("geom", invalidGeoJson))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coordinates");
  }

  /** 예외: GEOMETRY 타입 — 유효하지 않은 JSON 문자열이면 IllegalArgumentException */
  @Test
  void addRow_geometryType_invalidJson_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_bad_json", "geom", "GEOMETRY", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("geom", "not_json"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid GeoJSON");
  }

  /** 예외: GEOMETRY 타입에 문자열이 아닌 값을 넣으면 IllegalArgumentException */
  @Test
  void addRow_geometryType_withNonString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_non_str", "geom", "GEOMETRY", null, true);

    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("geom", 123))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GeoJSON string");
  }

  /** 정상: nullable 컬럼에 null 값 저장 성공 */
  @Test
  void addRow_nullableColumn_withNull_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_nullable", "opt", "TEXT", null, true);

    // null 값은 Map.of()로 표현할 수 없으므로 HashMap 사용
    Map<String, Object> data = new java.util.HashMap<>();
    data.put("opt", null);
    RowDataResponse row = datasetDataService.addRow(dataset.id(), new RowDataRequest(data));

    assertThat(row.id()).isNotNull();
    assertThat(row.data().get("opt")).isNull();
  }

  /** 예외: non-nullable 컬럼에 null 값 저장 시 IllegalArgumentException */
  @Test
  void addRow_nonNullableColumn_withNull_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_not_null", "required_col", "TEXT", null, false);

    Map<String, Object> data = new java.util.HashMap<>();
    data.put("required_col", null);
    assertThatThrownBy(() -> datasetDataService.addRow(dataset.id(), new RowDataRequest(data)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be null");
  }

  /** 예외: 배치 삽입에서 특정 행에 오류가 있으면 "Row N:" 접두사 포함 예외 */
  @Test
  void addRowsBatch_rowIndexInErrorMessage() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_batch_err", "num", "INTEGER", null, true);

    List<Map<String, Object>> rows =
        List.of(
            Map.of("num", 1), Map.of("num", 2), Map.of("num", "invalid") // Row 2에서 오류
            );

    assertThatThrownBy(
            () -> datasetDataService.addRowsBatch(dataset.id(), new BatchRowDataRequest(rows)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Row 2:");
  }

  // =========================================================================
  // propagateDescriptions
  // =========================================================================

  /** 정상: description이 있는 동일 컬럼명의 다른 데이터셋에서 description을 전파한다 */
  @Test
  void propagateDescriptions_copiesFromOtherDataset() {
    // 소스 데이터셋: "email" 컬럼에 description 있음
    List<DatasetColumnRequest> sourceColumns =
        List.of(new DatasetColumnRequest("email", "Email", "TEXT", null, true, false, "이메일 주소"));
    DatasetDetailResponse sourceDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Source DS", "source_ds", null, null, "SOURCE", sourceColumns, null),
            testUserId);

    // 대상 데이터셋: "email" 컬럼에 description 없음
    List<DatasetColumnRequest> targetColumns =
        List.of(new DatasetColumnRequest("email", "Email", "TEXT", null, true, false, null));
    DatasetDetailResponse targetDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Target DS", "target_ds", null, null, "SOURCE", targetColumns, null),
            testUserId);

    // description 전파 실행
    datasetDataService.propagateDescriptions(targetDataset.id());

    // 전파 후 대상 데이터셋 컬럼의 description이 채워졌는지 확인
    DatasetDetailResponse updated = datasetService.getDatasetById(targetDataset.id());
    DatasetColumnResponse emailCol =
        updated.columns().stream()
            .filter(c -> "email".equals(c.columnName()))
            .findFirst()
            .orElseThrow();
    assertThat(emailCol.description()).isEqualTo("이메일 주소");
  }

  /** 정상: 이미 description이 있는 컬럼은 전파로 덮어쓰지 않는다 */
  @Test
  void propagateDescriptions_doesNotOverwriteExistingDescription() {
    // 소스: "title" 컬럼에 description 있음
    List<DatasetColumnRequest> sourceColumns =
        List.of(new DatasetColumnRequest("title", "Title", "TEXT", null, true, false, "소스 설명"));
    DatasetDetailResponse sourceDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Prop Source", "prop_source", null, null, "SOURCE", sourceColumns, null),
            testUserId);

    // 대상: "title" 컬럼에 기존 description 있음
    List<DatasetColumnRequest> targetColumns =
        List.of(new DatasetColumnRequest("title", "Title", "TEXT", null, true, false, "기존 설명"));
    DatasetDetailResponse targetDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Prop Target", "prop_target", null, null, "SOURCE", targetColumns, null),
            testUserId);

    datasetDataService.propagateDescriptions(targetDataset.id());

    DatasetDetailResponse updated = datasetService.getDatasetById(targetDataset.id());
    DatasetColumnResponse titleCol =
        updated.columns().stream()
            .filter(c -> "title".equals(c.columnName()))
            .findFirst()
            .orElseThrow();
    // 기존 description이 유지되어야 함
    assertThat(titleCol.description()).isEqualTo("기존 설명");
  }

  /** 예외: 존재하지 않는 데이터셋에 propagateDescriptions 시 DatasetNotFoundException */
  @Test
  void propagateDescriptions_nonExistentDataset_throwsNotFound() {
    assertThatThrownBy(() -> datasetDataService.propagateDescriptions(999999L))
        .isInstanceOf(DatasetNotFoundException.class);
  }
}
