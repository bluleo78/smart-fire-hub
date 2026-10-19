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

  /** 테이블을 만든다. 이름·컬럼 정의가 잘못되면 예외를 던진다. 호출자 트랜잭션에 참여한다. */
  void createTable(String tableName, List<DatasetColumnRequest> columns);

  /** 기존 테이블에 컬럼을 추가한다. 호출자 트랜잭션에 참여한다. */
  void addColumn(String tableName, DatasetColumnRequest column);

  /** 컬럼 인덱스를 만들거나 지운다. {@code columnType} 은 인덱스 종류 판단용(없으면 null). 호출자 트랜잭션에 참여한다. */
  void setColumnIndex(String tableName, String columnName, boolean indexed, String columnType);

  /** PK 컬럼 기준 유니크 인덱스를 다시 만든다. 호출자 트랜잭션에 참여한다. */
  void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames);

  /** PK 유니크 인덱스를 잠금 없이(CONCURRENTLY) 만든다. 트랜잭션 밖에서 호출해야 한다. */
  void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames);

  /** 테이블을 지운다. 호출자 트랜잭션에 참여한다. */
  void dropTable(String tableName);

  /** REPLACE 적재용 임시(staging) 테이블 {@code {tableName}_tmp} 를 만든다. */
  void createTempTable(String tableName);

  /** REPLACE 적재를 마무리한다 — 쓴 행이 있으면 맞바꾸고, 없으면 임시 테이블만 버린다. */
  void finishReplace(String tableName, long rowsWritten);

  /** 임시 테이블과 원본을 맞바꾼다. 호출자 트랜잭션에 참여한다. */
  void swapTable(String tableName);

  /** 임시(staging) 테이블이 있으면 버린다. 실패 시 원본 보존용. */
  void dropTempTable(String tableName);

  /** 컬럼 이름을 바꾼다. 호출자 트랜잭션에 참여한다. */
  void renameColumn(String tableName, String oldName, String newName);

  /** 컬럼명 변경에 맞춰 해당 컬럼 인덱스 이름을 바꾼다. */
  void renameIndex(String tableName, String oldColName, String newColName);

  /** 컬럼 타입을 바꾼다. {@code currentDataType} 은 변환 가능 여부 판단용(없으면 null). 호출자 트랜잭션에 참여한다. */
  void alterColumnType(
      String tableName,
      String columnName,
      String dataType,
      Integer maxLength,
      String currentDataType);

  /** 컬럼의 NOT NULL 제약을 켜거나 끈다. */
  void setColumnNullable(String tableName, String columnName, boolean nullable);

  /** 컬럼을 지운다. 호출자 트랜잭션에 참여한다. */
  void dropColumn(String tableName, String columnName);

  /** 원본 테이블을 새 테이블로 복제한다(구조 + 지정한 사용자 컬럼 데이터). 호출자 트랜잭션에 참여한다. */
  void cloneTable(
      String sourceTable,
      String targetTable,
      List<String> userColumns,
      List<DatasetColumnResponse> columnDefs);

  /** 컬럼별 통계를 계산한다. 읽기 전용. */
  List<ColumnStatsResponse> getColumnStats(String tableName, List<DatasetColumnResponse> columns);
}
