package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.service.DataTableRowService;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 데이터셋 행 조회·적재의 백엔드별 구현 계약(WD-72).
 *
 * <p>기존 {@code DataTableRowService} 의 SQL 실행 메서드 중 "전체 인자" 변형과 같은 이름·인자·반환·예외를 갖는다. 기본 인자만 채워 다른
 * 오버로드로 넘기는 짧은 변형은 파사드({@code DataTableRowService})에 남는다. 파사드가 {@link DataStoreResolver} 로 테넌트 백엔드의
 * 구현을 골라 위임하며, 모든 메서드는 호출자의 트랜잭션에 참여한다. 반환 타입 {@code UpsertResult} 는 파사드의 중첩 레코드를 그대로 쓴다.
 *
 * <p>예외: 행 수 통계({@link #rowCounts}·{@link #emptyTables}, WD-80)는 파사드에 짝이 없다. 트리거 폴러·시스템 상태가 {@link
 * DataStoreResolver#rowStore()} 로 직접 부른다.
 */
public interface DataRowStore {

  /** 이 구현이 담당하는 백엔드. */
  DataStoreBackend backend();

  /** 컬럼명 목록으로 행을 검색·정렬·페이징 조회한다. GEOMETRY 컬럼은 GeoJSON 으로 변환한다(columnTypes 가 null 이면 변환 없음). */
  default List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      Map<String, String> columnTypes) {
    return queryData(tableName, columns, columns, search, page, size, sortBy, sortDir, columnTypes);
  }

  /**
   * 출력 컬럼({@code columns})과 검색어를 거는 컬럼({@code searchColumns})을 따로 받아 조회한다.
   *
   * <p>일부 컬럼만 내보낼 때도 검색은 데이터 탭처럼 전체 컬럼에 걸어야 화면과 같은 행이 나온다(WD-158).
   */
  List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      List<String> searchColumns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      Map<String, String> columnTypes);

  /** 컬럼 정의와 공간 필터를 포함해 행을 검색·정렬·페이징 조회한다. spatialFilter 가 null 이면 공간 조건 없음. */
  List<Map<String, Object>> queryData(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      String sortBy,
      String sortDir,
      int page,
      int size,
      SpatialFilter spatialFilter);

  /** 컬럼 정의와 공간 필터를 포함해 검색 조건에 맞는 행 수를 센다. spatialFilter 가 null 이면 공간 조건 없음. */
  long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      SpatialFilter spatialFilter);

  /** 컬럼명 목록·검색어 기준으로 행 수를 센다. */
  long countRows(
      String tableName, List<String> columns, String search, Map<String, String> columnTypes);

  /**
   * 테이블별 행 수를 센다(WD-80). DATASET_CHANGE 트리거 폴러가 변경 감지에 쓴다.
   *
   * <p>셀 수 없는 테이블(없음·오류)은 결과에서 빠지고, 이 메서드는 예외를 던지지 않는다. 실패를 0 으로 바꾸면 폴러가 "행 수가 바뀌었다"로 오인해 트리거를 잘못
   * 발동하므로, 호출자가 실패를 "값 없음"으로 구분할 수 있게 빼는 것이다. 값의 정확도는 백엔드마다 다르다(PostgreSQL 은 통계 추정치 {@code
   * n_live_tup}, Tibero 는 정확한 {@code COUNT(*)}).
   *
   * @param tableNames 메타데이터 테이블 이름(소문자)
   * @return {테이블 이름 → 행 수}. 센 테이블만 담긴다
   */
  Map<String, Long> rowCounts(Collection<String> tableNames);

  /**
   * 행이 하나도 없는 테이블 이름을 돌려준다(WD-80). 시스템 상태의 "빈 데이터셋 수"가 쓴다.
   *
   * <p>판정하지 못한 테이블(없음·오류)은 빠지고, 이 메서드는 예외를 던지지 않는다 — 판정 실패를 "비어 있음"으로 세지 않기 위해서다.
   *
   * @param tableNames 메타데이터 테이블 이름(소문자)
   * @return 비어 있다고 판정된 테이블 이름
   */
  Set<String> emptyTables(Collection<String> tableNames);

  /** PK 컬럼 조합이 모든 행에서 유일한지 검사한다. */
  boolean checkDataUniqueness(String tableName, List<String> pkColumnNames);

  /** 컬럼에 NULL 값이 한 건이라도 있는지 검사한다(NOT NULL 지정 전 사전 검사용). */
  boolean hasNulls(String tableName, String columnName);

  /** PK 컬럼 조합이 중복된 행을 최대 limit 건 조회한다. */
  List<Map<String, Object>> findDuplicateRows(
      String tableName, List<String> pkColumnNames, int limit);

  /** 행을 일괄 삽입한다(import_id 없음). 500행 단위 청크로 실행한다. */
  void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Map<String, String> columnTypes);

  /** 행을 일괄 삽입하며 청크마다 진행 콜백(처리 행 수, 전체 행 수)을 호출한다(import_id 없음). */
  void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes);

  /** 행을 일괄 삽입하며 모든 행의 import_id 를 importId 로 채운다. */
  void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes);

  /** import_id 를 채워 일괄 삽입하며 청크마다 진행 콜백을 호출한다. */
  void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes);

  /** PK 기준 UPSERT 로 행을 일괄 반영하고 신규 삽입·갱신 건수를 돌려준다. */
  DataTableRowService.UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes);

  /** PK 기준 UPSERT 를 청크마다 진행 콜백을 호출하며 실행하고 신규 삽입·갱신 건수를 돌려준다. */
  DataTableRowService.UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes);

  /** id 목록에 해당하는 행을 삭제하고 삭제된 행 수를 돌려준다. 목록이 비면 0. */
  int deleteRows(String tableName, List<Long> rowIds);

  /** 테이블의 모든 행을 비운다(파괴 연산). */
  void truncateTable(String tableName);

  /** 행 한 건을 삽입하고 생성된 id 를 돌려준다. */
  Long insertRow(
      String tableName,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes);

  /** id 로 행 한 건을 수정한다. 대상이 없으면 RowNotFoundException. */
  void updateRow(
      String tableName,
      long rowId,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes);

  /** id 로 행 한 건을 조회한다. 대상 행이 없으면 null 이 아니라 RowNotFoundException 을 던진다. */
  Map<String, Object> getRow(
      String tableName, List<String> columns, long rowId, Map<String, String> columnTypes);

  /** 대상 테이블 컬럼 구조를 본뜬 staging 테이블(PK 제약 없음, _seq 순번 포함)을 만들고 그 이름을 돌려준다. */
  String createStagingTable(String targetTableName, List<String> columns);

  /** staging 테이블에 행을 청크 단위로 삽입하며 진행 콜백을 호출한다(import_id 미사용). */
  void insertStagingBatchWithProgress(
      String stagingTable,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback);

  /** staging 내용을 PK별 마지막 등장 행만 골라 대상 테이블로 UPSERT 하고 신규 삽입·갱신 건수를 돌려준다. */
  DataTableRowService.UpsertResult promoteStagingToUpsert(
      String stagingTable,
      String targetTableName,
      List<String> columns,
      List<String> pkColumns,
      Long importId);

  /** 대상 테이블을 비운 뒤 staging 내용을 PK별 마지막 등장 행만 삽입해 교체한다. 호출자 트랜잭션 안에서 원자적으로 실행돼야 한다. */
  void promoteStagingToReplace(
      String stagingTable, String targetTableName, List<String> columns, List<String> pkColumns);

  /** staging 테이블이 있으면 삭제한다. import job 종료(성공·실패 무관) 뒤 항상 호출한다. */
  void dropStagingTable(String stagingTable);

  /**
   * 만든 지 {@code minAge} 가 지난 고아 staging 테이블을 지우고 지운 개수를 돌려준다(WD-74). 워커가 강제 종료돼 finally 의 {@link
   * #dropStagingTable} 이 돌지 못한 경우를 회수한다. 테넌트 컨텍스트 없이 부를 수 있도록 {@code tenantId} 를 직접 받는다.
   *
   * <p>{@code StagingTableCleanupService} 가 활성 작업 게이트 아래에서 외부 저장소 백엔드의 READY 테넌트마다 부른다. PostgreSQL
   * 은 그 서비스가 {@code data} 스키마를 직접 열거해 정리하므로 이 메서드를 쓰지 않는다(0 을 돌려준다).
   */
  int dropStaleStagingTables(long tenantId, Duration minAge);
}
