package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
  @Autowired private ObjectMapper objectMapper;

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
        new CreateDatasetRequest(name, tableName, null, null, "TABLE", "SOURCE", columns, null),
        testUserId);
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
        new CreateDatasetRequest(
            tableName, tableName, null, null, "TABLE", "SOURCE", columns, null),
        testUserId);
  }

  // =========================================================================
  // DOCUMENT 데이터셋 가드
  // =========================================================================

  /** 예외: DOCUMENT 데이터셋은 동적 테이블이 없으므로 행 조작이 400(IllegalArgumentException)으로 거부된다 */
  @Test
  void rowOps_documentDataset_throwsIllegalArgument() {
    DatasetDetailResponse doc =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Doc Rows", "doc_rows", null, null, "DOCUMENT", "SOURCE", List.of(), null),
            testUserId);

    assertThatThrownBy(() -> datasetDataService.getRowCount(doc.id()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> datasetDataService.addRow(doc.id(), new RowDataRequest(Map.of("name", "x"))))
        .isInstanceOf(IllegalArgumentException.class);
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
        .isInstanceOf(DatasetNotFoundException.class)
        // WD-240: 영어 「Dataset not found: N」 대신 한국어·번호 없는 문구
        .hasMessage("데이터셋을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
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
        .hasMessageContaining("문자열이어야 합니다");
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
        .hasMessageContaining("'col' 필드: 최대 5자까지 입력할 수 있습니다(입력 12자)");
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
        .hasMessageContaining("숫자 형식이 아닙니다");
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
        .hasMessageContaining("숫자 형식이 아닙니다");
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
        .hasMessageContaining("참/거짓(true/false) 값이어야 합니다");
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
        .hasMessageContaining("날짜 형식이 아닙니다 (yyyy-MM-dd)");
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
        .hasMessageContaining("날짜시간 형식이 아닙니다 (yyyy-MM-ddTHH:mm:ss)");
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
        .hasMessageContaining("날짜시간 형식이 아닙니다");
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

  /** 예외: GEOMETRY 타입 — 지원하지 않는 GeoJSON type이면 한국어 사유로 IllegalArgumentException */
  @Test
  void addRow_geometryType_unsupportedType_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_bad_type", "geom", "GEOMETRY", null, true);

    String invalidGeoJson = "{\"type\":\"Circle\",\"coordinates\":[127.0,37.5]}";
    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("geom", invalidGeoJson))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "입력값을 확인하세요: 'geom' 필드: 지원하지 않는 GeoJSON type 입니다: Circle"
                + " (Point·LineString·Polygon·MultiPoint·MultiLineString·MultiPolygon·GeometryCollection·Feature"
                + " 만 입력할 수 있습니다)");
  }

  /** 정상(WD-201): GeoJSON Feature 는 안의 geometry 로 저장된다 — Feature 자체가 아니라 POINT 가 들어가야 한다. */
  @Test
  void addRow_geometryType_feature_storesInnerGeometry() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd201_geom_feature", "geom", "GEOMETRY", null, true);

    String feature =
        "{\"type\":\"Feature\",\"properties\":{\"name\":\"서울\"},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[126.9779692,37.566535]}}";
    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("geom", feature)));

    // 좌표 자릿수까지 그대로 — Feature 에서 꺼낼 때 double 로 반올림되지 않는다.
    assertThat(readGeometryText(dataset.tableName(), "geom", row.id()))
        .isEqualTo("POINT(126.9779692 37.566535)");
  }

  /** 정상(WD-201): 행 편집에서도 Feature 를 받아 안의 geometry 로 바꿔 저장한다. */
  @Test
  void updateRow_geometryType_feature_storesInnerGeometry() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd201_geom_feature_upd", "geom", "GEOMETRY", null, true);
    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(),
            new RowDataRequest(Map.of("geom", "{\"type\":\"Point\",\"coordinates\":[1,2]}")));

    String feature =
        "{\"type\":\"Feature\",\"properties\":{},"
            + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[1,2],[3,4]]}}";
    datasetDataService.updateRow(
        dataset.id(), row.id(), new RowDataRequest(Map.of("geom", feature)));

    assertThat(readGeometryText(dataset.tableName(), "geom", row.id()))
        .isEqualTo("LINESTRING(1 2,3 4)");
  }

  /** 예외(WD-201): FeatureCollection 은 칸 하나에 담을 수 없어 한국어 사유로 거부한다. */
  @Test
  void addRow_geometryType_featureCollection_rejectedInKorean() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd201_geom_fc", "geom", "GEOMETRY", null, true);

    String fc = "{\"type\":\"FeatureCollection\",\"features\":[]}";
    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("geom", fc))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "입력값을 확인하세요: 'geom' 필드: FeatureCollection 은 저장할 수 없습니다."
                + " 한 칸에는 위치 하나만 들어가므로 Feature 하나나 geometry 를 입력하세요");
  }

  /** 예외(WD-201): geometry 가 null·없음인 Feature 는 거부한다(빈 위치는 값을 비워 두는 것으로 표현). */
  @Test
  void addRow_geometryType_featureWithoutGeometry_rejected() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd201_geom_feat_null", "geom", "GEOMETRY", null, true);

    for (String feature :
        List.of(
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":null}",
            "{\"type\":\"Feature\",\"coordinates\":[127.0,37.5]}")) {
      assertThatThrownBy(
              () ->
                  datasetDataService.addRow(
                      dataset.id(), new RowDataRequest(Map.of("geom", feature))))
          .as("value=%s", feature)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("입력값을 확인하세요: 'geom' 필드: Feature 에 geometry 가 없습니다. 위치가 없으면 값을 비워 두세요");
    }
  }

  /** 예외(WD-201): WKT 처럼 JSON 이 아닌 값은 한국어 사유만 — JSON 파서 원문(토큰·위치)을 싣지 않는다. */
  @Test
  void addRow_geometryType_wkt_rejectedWithoutParserDetail() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd201_geom_wkt", "geom", "GEOMETRY", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("geom", "POINT(1 2)"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "입력값을 확인하세요: 'geom' 필드: GeoJSON 형식이 아닙니다."
                + " 예: {\"type\":\"Point\",\"coordinates\":[126.97,37.56]}")
        .message()
        .doesNotContain("Unrecognized", "Source", "token");
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
        .hasMessageContaining("GeoJSON 형식이 아닙니다");
  }

  /** 예외: GEOMETRY 타입에 문자열이 아닌 값을 넣으면 IllegalArgumentException */
  @Test
  void addRow_geometryType_withNonString_throwsIllegalArgument() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_geom_non_str", "geom", "GEOMETRY", null, true);

    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("geom", 123))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GeoJSON 문자열이어야 합니다");
  }

  /** 정상: nullable 컬럼에 null 값 저장 성공 */
  @Test
  void addRow_nullableColumn_withNull_success() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_nullable", "opt", "TEXT", null, true);

    // null 값은 Map.of()로 표현할 수 없으므로 HashMap 사용
    Map<String, Object> data = new HashMap<>();
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

    Map<String, Object> data = new HashMap<>();
    data.put("required_col", null);
    assertThatThrownBy(() -> datasetDataService.addRow(dataset.id(), new RowDataRequest(data)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("입력값을 확인하세요: 'required_col' 필드: 필수 입력 항목입니다");
  }

  /** 예외: 배치 삽입에서 특정 행에 오류가 있으면 "N번째 행:" 접두사(1부터 셈) 포함 예외 */
  @Test
  void addRowsBatch_rowIndexInErrorMessage() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("type_batch_err", "num", "INTEGER", null, true);

    List<Map<String, Object>> rows =
        List.of(
            Map.of("num", 1), Map.of("num", 2), Map.of("num", "invalid") // 3번째 행에서 오류
            );

    assertThatThrownBy(
            () -> datasetDataService.addRowsBatch(dataset.id(), new BatchRowDataRequest(rows)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3번째 행: 입력값을 확인하세요: 'num' 필드:");
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
                "Source DS", "source_ds", null, null, "TABLE", "SOURCE", sourceColumns, null),
            testUserId);

    // 대상 데이터셋: "email" 컬럼에 description 없음
    List<DatasetColumnRequest> targetColumns =
        List.of(new DatasetColumnRequest("email", "Email", "TEXT", null, true, false, null));
    DatasetDetailResponse targetDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Target DS", "target_ds", null, null, "TABLE", "SOURCE", targetColumns, null),
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
                "Prop Source", "prop_source", null, null, "TABLE", "SOURCE", sourceColumns, null),
            testUserId);

    // 대상: "title" 컬럼에 기존 description 있음
    List<DatasetColumnRequest> targetColumns =
        List.of(new DatasetColumnRequest("title", "Title", "TEXT", null, true, false, "기존 설명"));
    DatasetDetailResponse targetDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Prop Target", "prop_target", null, null, "TABLE", "SOURCE", targetColumns, null),
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

  // =========================================================================
  // updateRow — 부분 업데이트(merge-then-validate) (#672)
  // =========================================================================

  /**
   * 정상: 사용자 정의 PK(NOT NULL) 컬럼이 요청 바디에서 빠져도(프론트 편집 폼이 읽기 전용으로 제외한 경우를 시뮬레이션) 기존 DB 값이 그대로 유지된 채 다른
   * 컬럼만 갱신되어야 한다.
   *
   * <p>수정 전에는 updateRow가 요청 바디만으로 검증해 빠진 PK 컬럼을 null로 취급, NOT NULL 검증 실패(400)로 항상 저장이 막혔다.
   */
  @Test
  void updateRow_partialUpdate_missingPkColumn_keepsExistingValue() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest(
                "row_key", "Row Key", "INTEGER", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "PK Update Test", "pk_update_test", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);

    RowDataResponse added =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("row_key", 1, "label", "before")));

    // 프론트 EditRowDialog가 PK 컬럼(row_key)을 폼에서 제외하는 상황을 재현 — 요청 바디에 row_key 없음
    datasetDataService.updateRow(
        dataset.id(), added.id(), new RowDataRequest(Map.of("label", "after")));

    RowDataResponse result = datasetDataService.getRow(dataset.id(), added.id());
    assertThat(result.data().get("row_key")).isEqualTo(1L);
    assertThat(result.data().get("label")).isEqualTo("after");
  }

  /** 정상: 일반(PK 미지정) 데이터셋의 전체 필드 포함 행 편집은 기존과 동일하게 정상 동작해야 한다 (회귀 없음) */
  @Test
  void updateRow_normalDataset_fullPayload_updatesAllFields() {
    DatasetDetailResponse dataset = createSimpleDataset("Update Normal", "update_normal");
    RowDataResponse added =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("name", "Old", "value", 1)));

    datasetDataService.updateRow(
        dataset.id(), added.id(), new RowDataRequest(Map.of("name", "New", "value", 2)));

    RowDataResponse result = datasetDataService.getRow(dataset.id(), added.id());
    assertThat(result.data().get("name")).isEqualTo("New");
    assertThat(result.data().get("value")).isEqualTo(2L);
  }

  /**
   * 회귀(#670): NULL 허용 BOOLEAN 컬럼에 요청 바디로 명시적 null을 보내면(값 없음으로 지우는 경우) 병합 로직이 "요청에 없는 컬럼"으로 오인해 기존
   * 값을 덮어써 되살리면 안 되고, 요청된 null이 그대로 반영되어야 한다.
   */
  @Test
  void updateRow_nullableBooleanExplicitNull_clearsValue() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("bool_null_update", "active", "BOOLEAN", null, true);
    RowDataResponse added =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("active", true)));

    // HashMap 사용 — Map.of는 null 값을 허용하지 않으므로 명시적 null 요청을 표현하려면 HashMap 필요
    Map<String, Object> nullActive = new HashMap<>();
    nullActive.put("active", null);
    datasetDataService.updateRow(dataset.id(), added.id(), new RowDataRequest(nullActive));

    RowDataResponse result = datasetDataService.getRow(dataset.id(), added.id());
    assertThat(result.data().get("active")).isNull();
  }

  /** 회귀(#670): 부분 업데이트 병합 후에도 NULL 허용 BOOLEAN 컬럼을 요청에서 생략하면 기존 값(NULL 포함)이 유지되어야 한다 */
  @Test
  void updateRow_nullableBooleanOmittedFromRequest_keepsExistingNull() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("bool_null_keep", "flag", "BOOLEAN", null, true);
    Map<String, Object> initialNull = new HashMap<>();
    initialNull.put("flag", null);
    RowDataResponse added =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(initialNull));

    // flag를 요청 바디에서 아예 생략 — 기존 NULL 값이 그대로 유지되어야 함
    datasetDataService.updateRow(dataset.id(), added.id(), new RowDataRequest(Map.of()));

    RowDataResponse result = datasetDataService.getRow(dataset.id(), added.id());
    assertThat(result.data().get("flag")).isNull();
  }

  /** 예외: 부분 업데이트 병합 후에도 요청에 포함된 컬럼의 타입/필수 검증은 여전히 수행되어야 한다 */
  @Test
  void updateRow_partialUpdate_invalidRequestedColumn_stillValidates() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest(
                "row_key", "Row Key", "INTEGER", null, false, false, null, true),
            new DatasetColumnRequest("label", "Label", "TEXT", null, false, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "PK Update Invalid",
                "pk_update_invalid",
                null,
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);

    RowDataResponse added =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("row_key", 1, "label", "before")));

    // label은 NOT NULL인데 빈 문자열이 아닌 null을 명시적으로 보냄 — 여전히 검증 실패해야 함
    Map<String, Object> invalid = new HashMap<>();
    invalid.put("label", null);
    assertThatThrownBy(
            () ->
                datasetDataService.updateRow(dataset.id(), added.id(), new RowDataRequest(invalid)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // =========================================================================
  // updateRow — 요청에 없는 컬럼은 UPDATE 대상에서 빠진다 (#772)
  // =========================================================================

  /** label(TEXT) + d(DATE) + ts(TIMESTAMP) + geom(GEOMETRY) 데이터셋을 만들고 행 하나를 직접 SQL 로 넣는다. */
  private Object[] createTemporalGeomRow(String tableName, String d, String ts, String geomWkt) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("label", "label", "TEXT", null, true, false, null),
            new DatasetColumnRequest("d", "d", "DATE", null, true, false, null),
            new DatasetColumnRequest("ts", "ts", "TIMESTAMP", null, true, false, null),
            new DatasetColumnRequest("geom", "geom", "GEOMETRY", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                tableName, tableName, null, null, "TABLE", "SOURCE", columns, null),
            testUserId);
    // API 경로(addRow)는 ±infinity·BC·9자리 넘는 좌표를 만들 수 없으므로 PG 에 직접 넣는다
    Long rowId =
        dsl.fetchOne(
                "INSERT INTO data.\""
                    + dataset.tableName()
                    + "\" (label, d, ts, geom)"
                    + " VALUES ('before', ?::date, ?::timestamp, ST_GeomFromText(?, 4326)) RETURNING id",
                d,
                ts,
                geomWkt)
            .get(0, Long.class);
    return new Object[] {dataset, rowId};
  }

  private Map<String, Object> readRawRow(String tableName, Long rowId) {
    return dsl.fetchOne(
            "SELECT label, d::text AS d, ts::text AS ts, ST_AsEWKT(geom) AS geom FROM data.\""
                + tableName
                + "\" WHERE id = ?",
            rowId)
        .intoMap();
  }

  /**
   * 회귀(#772): 한 컬럼만 바꾸는 부분 업데이트가 손대지 않은 GEOMETRY 를 다시 쓰면 안 된다.
   *
   * <p>수정 전에는 행 전체를 읽어(ST_AsGeoJSON, 소수 9자리) 모든 컬럼을 다시 UPDATE 해, label 만 바꿔도 9자리를 넘는 좌표가 조용히 반올림됐다.
   * 이제는 요청에 포함된 컬럼만 SET 한다.
   */
  @Test
  void updateRow_partialUpdate_untouchedGeometry_keepsFullPrecision() {
    Object[] created =
        createTemporalGeomRow(
            "geom_precision_keep",
            "2020-01-03",
            "2020-01-03 10:15:30.5",
            "POINT(126.978123456789012 37.566512345678901)");
    DatasetDetailResponse dataset = (DatasetDetailResponse) created[0];
    Long rowId = (Long) created[1];
    Map<String, Object> before = readRawRow(dataset.tableName(), rowId);

    datasetDataService.updateRow(dataset.id(), rowId, new RowDataRequest(Map.of("label", "after")));

    Map<String, Object> after = readRawRow(dataset.tableName(), rowId);
    assertThat(after.get("label")).isEqualTo("after");
    assertThat(after.get("geom")).isEqualTo(before.get("geom"));
    assertThat(after.get("d")).isEqualTo("2020-01-03");
    assertThat(after.get("ts")).isEqualTo("2020-01-03 10:15:30.5");
  }

  /**
   * 회귀(#772): 입력란이 표현할 수 없는 날짜·시각(infinity, BC)이 있는 행에서 다른 컬럼만 바꿔도 그 값이 그대로 남아야 한다(요청에 없는 컬럼은 다시 쓰지
   * 않는다).
   */
  @Test
  void updateRow_partialUpdate_untouchedOutOfRangeTemporal_keepsPgValue() {
    Object[] created =
        createTemporalGeomRow(
            "temporal_odd_keep", "infinity", "0044-03-15 10:00:00 BC", "POINT(1 2)");
    DatasetDetailResponse dataset = (DatasetDetailResponse) created[0];
    Long rowId = (Long) created[1];

    datasetDataService.updateRow(dataset.id(), rowId, new RowDataRequest(Map.of("label", "after")));

    Map<String, Object> after = readRawRow(dataset.tableName(), rowId);
    assertThat(after.get("label")).isEqualTo("after");
    assertThat(after.get("d")).isEqualTo("infinity");
    assertThat(after.get("ts")).isEqualTo("0044-03-15 10:00:00 BC");
  }

  /** 예외(#772): 요청 컬럼이 하나도 없어도 없는 행이면 여전히 RowNotFound 로 거절된다. */
  @Test
  void updateRow_emptyRequest_missingRow_throwsRowNotFound() {
    DatasetDetailResponse dataset =
        createSimpleDataset("Update Empty Missing", "update_empty_missing");
    assertThatThrownBy(
            () ->
                datasetDataService.updateRow(dataset.id(), 999_999L, new RowDataRequest(Map.of())))
        .isInstanceOf(com.smartfirehub.dataset.exception.RowNotFoundException.class);
  }

  // =========================================================================
  // 물리 타입 한도 검사 + DB 데이터 오류 번역 (WD-98)
  // =========================================================================

  /** 물리 컬럼 값을 텍스트로 읽는다 — 검증이 정규화한 "실제 저장값"을 DB 기준으로 확인하기 위함. */
  private String readRawText(String tableName, String column, Long rowId) {
    return dsl.fetchOne(
            "SELECT \"" + column + "\"::text AS v FROM data.\"" + tableName + "\" WHERE id = ?",
            rowId)
        .get("v", String.class);
  }

  /** 위치 칸에 실제로 저장된 geometry 를 WKT 로 읽는다 — Feature 가 아니라 도형이 들어갔는지 확인하는 용도(WD-201). */
  private String readGeometryText(String tableName, String column, Long rowId) {
    return dsl.fetchOne(
            "SELECT ST_AsText(\""
                + column
                + "\") AS v FROM data.\""
                + tableName
                + "\" WHERE id = ?",
            rowId)
        .get("v", String.class);
  }

  /**
   * 회귀(WD-98): 소수 정수부 13자리는 INSERT 의 영어 「Data integrity violation」(409)이 아니라, 검증 단계에서 필드명과 한국어 사유가
   * 담긴 400(IllegalArgumentException)으로 거부된다. 사유 문구까지 고정한다 — DB 오류 번역(같은 400·한국어)만으로도 통과하면 검증이 빠져도
   * 모르기 때문이다.
   */
  @Test
  void addRow_decimal_integerPart13Digits_rejectedWithKoreanReason() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_dec_13", "price", "DECIMAL", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(),
                    new RowDataRequest(Map.of("price", new BigDecimal("1234567890123")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'price' 필드: 정수부는 최대 12자리까지 입력할 수 있습니다")
        .hasMessageNotContaining("저장하지 못했습니다");
  }

  /** 정상(WD-98): 정수부 12자리·소수 6자리 경계 값은 그대로 저장된다. */
  @Test
  void addRow_decimal_integerPart12Digits_saved() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_dec_12", "price", "DECIMAL", null, true);

    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(),
            new RowDataRequest(Map.of("price", new BigDecimal("-999999999999.999999"))));

    assertThat(readRawText(dataset.tableName(), "price", row.id()))
        .isEqualTo("-999999999999.999999");
  }

  /**
   * 회귀(WD-98): 행 API 는 엄격 모드 — 소수부 7자리 이상(끝 0 제외)은 반올림 저장하지 않고 거부한다(임포트는 PG 규칙대로 반올림). JSON 숫자가
   * Double 로 들어오는 경우(1.0E-7 표기)와 문자열 모두 같다. 끝 0 만 붙은 값은 저장값이 같아 통과한다.
   */
  @Test
  void addRow_decimal_sevenFractionDigits_rejectedStrict() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_dec_strict", "price", "DECIMAL", null, true);

    for (Object value : List.of(0.0000001, "0.0000001", "1.1234567", "1e-7")) {
      assertThatThrownBy(
              () ->
                  datasetDataService.addRow(
                      dataset.id(), new RowDataRequest(Map.of("price", value))))
          .as("value=%s", value)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'price' 필드: 소수점 아래는 최대 6자리까지 입력할 수 있습니다");
    }

    RowDataResponse trailingZeros =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("price", "1.5000000")));
    assertThat(readRawText(dataset.tableName(), "price", trailingZeros.id())).isEqualTo("1.500000");
  }

  /**
   * 회귀(WD-98): 웹 폼이 보내는 문자열 DECIMAL 은 정확히 저장된다 — JSON 숫자(JS double)로 보내던 때는 유효숫자 18자리 값이
   * 123456789012.12346 으로 깎였다. 형식이 틀린 문자열은 형식 오류다.
   */
  @Test
  void addRow_decimal_string18SignificantDigits_savedExactly() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_dec_exact", "price", "DECIMAL", null, true);

    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("price", "123456789012.123456")));
    assertThat(readRawText(dataset.tableName(), "price", row.id()))
        .isEqualTo("123456789012.123456");

    for (String bad : List.of("1,000", "NaN", " ", "12abc")) {
      assertThatThrownBy(
              () ->
                  datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("price", bad))))
          .as("value=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'price' 필드: 숫자 형식이 아닙니다");
    }
  }

  /**
   * 회귀(WD-98): INTEGER 는 값이 조용히 바뀌지 않는다 — 예전 {@code longValue()} 는 2^64+1 → 1, 1.9 → 1, 1e30 →
   * Long.MAX 로 저장했다. 정수가 아니면 형식 오류, BIGINT 범위 밖이면 범위 오류(임포트와 같은 문구). 정수 값의 Double(3.0)과 경계 값은 저장된다.
   */
  @Test
  void addRow_integer_inexactValues_rejected() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_int_exact", "num", "INTEGER", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(),
                    new RowDataRequest(
                        Map.of(
                            "num",
                            java.math.BigInteger.TWO.pow(64).add(java.math.BigInteger.ONE)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'num' 필드: 정수 범위를 벗어났습니다: 18446744073709551617");
    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("num", 1e30))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'num' 필드: 정수 범위를 벗어났습니다");
    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("num", 1.9))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'num' 필드: 정수 형식이 아닙니다: 1.9");

    RowDataResponse whole =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("num", 3.0)));
    RowDataResponse max =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("num", Long.MAX_VALUE)));
    assertThat(readRawText(dataset.tableName(), "num", whole.id())).isEqualTo("3");
    assertThat(readRawText(dataset.tableName(), "num", max.id()))
        .isEqualTo(String.valueOf(Long.MAX_VALUE));
  }

  /**
   * 회귀(WD-98): 웹 폼은 INTEGER 도 입력 원문 문자열로 보낸다 — JS 숫자는 2^53 을 넘는 BIGINT 를 깎는다(9007199254740993 →
   * 9007199254740992). 문자열은 정확히 저장되고, 정수가 아니거나 범위를 넘는 문자열은 숫자와 같은 사유로 거부된다.
   */
  @Test
  void addRow_integer_stringBeyond2pow53_savedExactly() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_int_str", "num", "INTEGER", null, true);

    RowDataResponse row =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("num", " 9007199254740993 ")));
    assertThat(readRawText(dataset.tableName(), "num", row.id())).isEqualTo("9007199254740993");

    assertThatThrownBy(
            () -> datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("num", "1.9"))))
        .hasMessageContaining("'num' 필드: 정수 형식이 아닙니다: 1.9");
    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("num", "9223372036854775808"))))
        .hasMessageContaining("'num' 필드: 정수 범위를 벗어났습니다: 9223372036854775808");
  }

  /** 회귀(WD-98): 행 API 는 엄격 모드 — 초과분이 공백뿐이어도(PG 는 잘라 저장) 길이 초과로 거부한다. */
  @Test
  void addRow_varchar_trailingSpaceOverflow_rejectedStrict() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_vc_space", "col", "VARCHAR", 3, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("col", "abc  "))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'col' 필드: 최대 3자까지 입력할 수 있습니다(입력 5자)");
  }

  /** 회귀(WD-98): VARCHAR 길이는 UTF-16 이 아니라 문자(코드포인트) 수로 센다 — 이모지 5자는 VARCHAR(5)에 들어간다. */
  @Test
  void addRow_varchar_fiveEmojiInVarchar5_saved() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_emoji", "col", "VARCHAR", 5, true);
    String emoji = "😀😀😀😀😀"; // UTF-16 길이 10, 코드포인트 5

    RowDataResponse row =
        datasetDataService.addRow(dataset.id(), new RowDataRequest(Map.of("col", emoji)));

    assertThat(readRawText(dataset.tableName(), "col", row.id())).isEqualTo(emoji);
  }

  /** 회귀(WD-98): 길이 미지정 VARCHAR(물리 VARCHAR(255))도 검사한다 — 256자는 DB 오류가 아니라 검증 단계 사유로 거부된다. */
  @Test
  void addRow_varchar_unspecifiedLength_256Chars_rejected() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_vc_default", "col", "VARCHAR", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("col", "a".repeat(256)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'col' 필드: 최대 255자까지 입력할 수 있습니다(입력 256자)");
  }

  /** 회귀(WD-98): NUL 문자는 INSERT 의 22021 이 아니라 검증 단계에서 거부된다(TEXT·VARCHAR 모두). */
  @Test
  void addRow_textWithNul_rejected() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_nul", "col", "TEXT", null, true);

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("col", "a\u0000b"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'col' 필드: 저장할 수 없는 문자(NUL)가 포함되어 있습니다");
  }

  /** 회귀(WD-98): 행 수정도 같은 검사를 거친다 — 정수부 13자리·길이 초과는 거부, 경계 값은 저장. */
  @Test
  void updateRow_appliesSamePhysicalLimits() {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("price", "가격", "DECIMAL", null, true, false, null),
            new DatasetColumnRequest("code", "코드", "VARCHAR", 3, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "WD98 Update", "wd98_update", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);
    RowDataResponse added =
        datasetDataService.addRow(
            dataset.id(), new RowDataRequest(Map.of("price", 1, "code", "abc")));

    assertThatThrownBy(
            () ->
                datasetDataService.updateRow(
                    dataset.id(),
                    added.id(),
                    new RowDataRequest(Map.of("price", new BigDecimal("12345678901234")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'가격(price)' 필드: 정수부는 최대 12자리까지 입력할 수 있습니다");
    assertThatThrownBy(
            () ->
                datasetDataService.updateRow(
                    dataset.id(), added.id(), new RowDataRequest(Map.of("code", "abcd"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'코드(code)' 필드: 최대 3자까지 입력할 수 있습니다(입력 4자)");

    datasetDataService.updateRow(
        dataset.id(),
        added.id(),
        new RowDataRequest(Map.of("price", new BigDecimal("123456789012.5"), "code", "😀😀😀")));
    assertThat(readRawText(dataset.tableName(), "price", added.id()))
        .isEqualTo("123456789012.500000");
    assertThat(readRawText(dataset.tableName(), "code", added.id())).isEqualTo("😀😀😀");
  }

  /**
   * 회귀(WD-98): 검증을 통과한 값이 쓰기에서 DB 데이터 오류(22xxx)로 실패하면, 실제 jOOQ/Spring 체인이 {@link
   * DataIntegrityViolationException} 을 내고 {@link DataExceptionTranslator#describe} 가 한국어 사유를 찾는다 —
   * 전역 처리기가 이 사유로 400 을 만든다(응답 형태는 GlobalExceptionHandlerTest). 메타데이터(VARCHAR(10))와 물리
   * 컬럼(VARCHAR(3))을 어긋나게 해 22001 을 일으킨다. 실패 뒤 트랜잭션이 중단(25P02)되므로 이후 DB 문은 실행하지 않는다.
   */
  @Test
  void addRow_dbDataError_reachesHandlerAsTranslatableIntegrityViolation() {
    DatasetDetailResponse dataset =
        createSingleColumnDataset("wd98_db_err", "code", "VARCHAR", 10, true);
    dsl.execute(
        "ALTER TABLE data.\"" + dataset.tableName() + "\" ALTER COLUMN code TYPE varchar(3)");

    assertThatThrownBy(
            () ->
                datasetDataService.addRow(
                    dataset.id(), new RowDataRequest(Map.of("code", "abcdef"))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            e ->
                assertThat(DataExceptionTranslator.describe(e))
                    .isEqualTo("글자 수 제한(최대 3자)을 넘는 값이 있습니다"));
  }

  // =========================================================================
  // 행 응답 JSON 정밀도 (WD-101)
  // =========================================================================

  /**
   * 정상: 실제 DB 의 NUMERIC(18,6)·BIGINT 값이 앱 ObjectMapper 로 직렬화될 때 JS double 로 깎이는 값은 문자열로, 작은 정수는 숫자로
   * 나간다. 컨트롤러 슬라이스 테스트는 목 값만 쓰므로, 행 조회가 실제로 BigDecimal·Long 을 돌려준다는 전제까지 여기서 잇는다.
   */
  @Test
  void rowResponses_jsonKeepsDecimalAndUnsafeBigintPrecision() throws Exception {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("price", "Price", "DECIMAL", null, true, false, null),
            new DatasetColumnRequest("big", "Big", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("small", "Small", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("half", "Half", "DECIMAL", null, true, false, null),
            new DatasetColumnRequest("empty", "Empty", "DECIMAL", null, true, false, null));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Precision", "wd101_precision", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);
    Map<String, Object> input = new HashMap<>();
    input.put("price", "123456789012.123456");
    input.put("big", "9007199254740993");
    input.put("small", 5);
    input.put("half", "1.5");
    input.put("empty", null);
    RowDataResponse added = datasetDataService.addRow(dataset.id(), new RowDataRequest(input));

    // 목록 조회(데이터 탭 그리드)
    JsonNode listed =
        objectMapper
            .readTree(
                objectMapper.writeValueAsString(
                    datasetDataService.getDatasetData(dataset.id(), null, 0, 10)))
            .path("rows")
            .path(0);
    assertThat(listed.path("price").isTextual()).isTrue();
    assertThat(listed.path("price").asText()).isEqualTo("123456789012.123456");
    assertThat(listed.path("big").isTextual()).isTrue();
    assertThat(listed.path("big").asText()).isEqualTo("9007199254740993");
    assertThat(listed.path("small").isIntegralNumber()).isTrue();
    assertThat(listed.path("small").asLong()).isEqualTo(5L);
    assertThat(listed.path("half").asText()).isEqualTo("1.5");
    assertThat(listed.path("empty").isNull()).isTrue();
    // 행 id 는 웹이 typeof === 'number' 로 걸러 쓰므로 숫자로 남아야 한다.
    assertThat(listed.path("id").isIntegralNumber()).isTrue();

    // 단건 조회(행 편집 다이얼로그)
    JsonNode single =
        objectMapper
            .readTree(
                objectMapper.writeValueAsString(
                    datasetDataService.getRow(dataset.id(), added.id())))
            .path("data");
    assertThat(single.path("price").asText()).isEqualTo("123456789012.123456");
    assertThat(single.path("big").isTextual()).isTrue();
    assertThat(single.path("big").asText()).isEqualTo("9007199254740993");
    assertThat(single.path("small").isIntegralNumber()).isTrue();
  }
}
