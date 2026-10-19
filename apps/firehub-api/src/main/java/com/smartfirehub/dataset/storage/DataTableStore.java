package com.smartfirehub.dataset.storage;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.util.List;
import java.util.Set;

/**
 * 데이터셋 테이블 DDL·프로파일링의 백엔드별 구현 계약(WD-72).
 *
 * <p>기존 {@code DataTableService} 의 SQL 실행 메서드와 같은 이름·인자·반환·예외를 갖는다. 호출부는 파사드({@code
 * DataTableService})만 알고, 파사드가 {@link DataStoreResolver} 로 테넌트 백엔드의 구현을 골라 위임한다. 모든 메서드는 호출자의 트랜잭션에
 * 참여한다.
 */
public interface DataTableStore {

  /** 행 변경 시각 시스템 컬럼 — 파이프라인 증분 처리({{last_run_at}})의 기준. 트리거가 관리한다. */
  String UPDATED_AT_COLUMN = "_updated_at";

  /** 사용자가 컬럼명으로 쓸 수 없는 시스템 예약 컬럼. 생성·컬럼 추가·결과 숨김이 모두 이 집합을 따른다. */
  Set<String> SYSTEM_COLUMNS = Set.of("id", "import_id", "created_at", UPDATED_AT_COLUMN);

  /** 이 구현이 담당하는 백엔드. */
  DataStoreBackend backend();

  void createTable(String tableName, List<DatasetColumnRequest> columns);

  void addColumn(String tableName, DatasetColumnRequest column);

  void setColumnIndex(String tableName, String columnName, boolean indexed, String columnType);

  void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames);

  void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames);

  void dropTable(String tableName);

  void createTempTable(String tableName);

  void finishReplace(String tableName, long rowsWritten);

  void swapTable(String tableName);

  void dropTempTable(String tableName);

  void renameColumn(String tableName, String oldName, String newName);

  void renameIndex(String tableName, String oldColName, String newColName);

  void alterColumnType(
      String tableName,
      String columnName,
      String dataType,
      Integer maxLength,
      String currentDataType);

  void setColumnNullable(String tableName, String columnName, boolean nullable);

  void dropColumn(String tableName, String columnName);

  void cloneTable(
      String sourceTable,
      String targetTable,
      List<String> userColumns,
      List<DatasetColumnResponse> columnDefs);

  List<ColumnStatsResponse> getColumnStats(String tableName, List<DatasetColumnResponse> columns);
}
