package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.dataset.storage.DataTableStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * 데이터셋 테이블 DDL 파사드 — 테넌트 저장소 백엔드에 위임(WD-72).
 *
 * <p>호출부는 이 클래스만 알면 된다. 실제 SQL 은 {@link DataStoreResolver} 가 현재 테넌트 기준으로 고른 {@link DataTableStore}
 * 구현이 실행하며, 호출자의 트랜잭션에 그대로 참여한다. 이름 검증({@link #validateName})처럼 백엔드와 무관한 순수 검증과, 기본 인자만 채워 다른 오버로드로
 * 넘기는 편의 오버로드는 여기에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class DataTableService {

  private final DataStoreResolver resolver;

  /**
   * 새로 만드는 테이블명의 최대 길이(WD-95).
   *
   * <p>PostgreSQL 은 식별자를 63바이트에서 조용히 자른다({@link DataTableNames} 이 ASCII 만 허용하므로 글자 수 = 바이트 수). 상한이 없으면
   * 앞 63자가 같은 두 이름이 같은 물리 테이블이 돼 고아 회수 DROP 이 남의 데이터를 지우고, 63자 넘는 컬럼명은 조회 결과 키가 메타데이터와 어긋난다. 테이블은
   * 63 이 아니라 56 인데, 이름이 V125 와 같은 규약으로 고정된 {@code ix_<table>_upd}(+7)까지 63 안에 들어와야 하기 때문이다.
   */
  public static final int MAX_TABLE_NAME_LENGTH = 56;

  /** 새로 만드는 컬럼명의 최대 길이 — 근거는 {@link #MAX_TABLE_NAME_LENGTH}. */
  public static final int MAX_COLUMN_NAME_LENGTH = 63;

  /**
   * REPLACE 적재용 임시 테이블 접두어 — 사용자 테이블명으로는 예약한다({@link #rejectReservedTableName}). 이름 조립은 {@link
   * #tempTableName} 단독.
   */
  public static final String TEMP_TABLE_PREFIX = "rtmp_";

  /** 행 변경 시각 시스템 컬럼 — 파이프라인 증분 처리({{last_run_at}})의 기준. 트리거가 관리한다. */
  public static final String UPDATED_AT_COLUMN = DataTableStore.UPDATED_AT_COLUMN;

  /** 사용자가 컬럼명으로 쓸 수 없는 시스템 예약 컬럼. 생성·컬럼 추가·결과 숨김이 모두 이 집합을 따른다. */
  public static final Set<String> SYSTEM_COLUMNS = DataTableStore.SYSTEM_COLUMNS;

  /**
   * {@link #validateNewColumns} 의 타입 검증 전용 — 실제 DDL 타입 문자열은 백엔드 구현({@code
   * PostgresDataTableStore#mapDataType})이 만든다. 여기서는 알 수 없는 타입·VARCHAR 길이 범위 위반만 걸러낸다.
   */
  private String mapDataType(String dataType, Integer maxLength) {
    return switch (dataType) {
      case "TEXT" -> "TEXT";
      case "VARCHAR" -> {
        int len = maxLength != null ? maxLength : ColumnPhysicalType.DEFAULT_VARCHAR_LENGTH;
        if (len < 1 || len > 10000) {
          throw new IllegalArgumentException("VARCHAR length must be between 1 and 10000");
        }
        yield "VARCHAR(" + len + ")";
      }
      case "INTEGER" -> "BIGINT";
      // 값 검사(ColumnPhysicalType.enforceLimits)와 같은 상수로 조립해야 "검증 통과 == INSERT 성공"이 유지된다.
      case "DECIMAL" ->
          "NUMERIC("
              + ColumnPhysicalType.DECIMAL_PRECISION
              + ","
              + ColumnPhysicalType.DECIMAL_SCALE
              + ")";
      case "BOOLEAN" -> "BOOLEAN";
      case "DATE" -> "DATE";
      case "TIMESTAMP" -> "TIMESTAMP";
      case "GEOMETRY" -> "GEOMETRY(Geometry, 4326)";
      default -> throw new IllegalArgumentException("Unknown data type: " + dataType);
    };
  }

  /** 이름이 {@code [a-z][a-z0-9_]*} 패턴인지 검증한다(백엔드 무관). 어긋나면 {@link InvalidTableNameException}. */
  public void validateName(String name) {
    DataTableNames.validate(name);
  }

  /**
   * <b>새로 만드는</b> 테이블명 검증 — 형식({@link #validateName}) + 길이 상한({@link #MAX_TABLE_NAME_LENGTH}) + 시스템
   * 예약 접두어({@link #rejectReservedTableName}). 물리 테이블을 만들지 않는 DOCUMENT/FILE 데이터셋도 이 이름 공간을 쓰므로 같은
   * 검사를 받는다.
   *
   * <p>새 이름이 생기는 경로(생성·복제)에서만 쓴다. 기존 이름을 다루는 경로는 {@link #validateName} 만 쓴다 — 상한 전에 만들어진 긴 이름 데이터셋이
   * 계속 동작해야 한다.
   */
  public void validateNewTableName(String tableName) {
    validateName(tableName);
    if (tableName.length() > MAX_TABLE_NAME_LENGTH) {
      throw new InvalidTableNameException(
          "테이블명은 최대 " + MAX_TABLE_NAME_LENGTH + "자까지 쓸 수 있습니다(입력 " + tableName.length() + "자)");
    }
    rejectReservedTableName(tableName);
  }

  /**
   * <b>새로 만드는</b> 컬럼명 검증 — 시스템 예약어({@link #SYSTEM_COLUMNS}) + 형식 + 길이 상한({@link
   * #MAX_COLUMN_NAME_LENGTH}). 생성·추가·이름 변경에서만 쓴다.
   */
  public void validateNewColumnName(String columnName) {
    // 예약어를 형식보다 먼저 본다 — _updated_at 처럼 형식에도 걸리는 이름에 "예약어"라는 정확한 이유를 준다.
    if (SYSTEM_COLUMNS.contains(columnName.toLowerCase())) {
      throw new InvalidTableNameException(
          "컬럼명 '" + columnName + "'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)");
    }
    validateName(columnName);
    if (columnName.length() > MAX_COLUMN_NAME_LENGTH) {
      throw new InvalidTableNameException(
          "필드명은 최대 " + MAX_COLUMN_NAME_LENGTH + "자까지 쓸 수 있습니다(입력 " + columnName.length() + "자)");
    }
  }

  /**
   * <b>새로 만드는</b> 컬럼 정의 전체 검증 — 이름({@link #validateNewColumnName})과 데이터 타입(VARCHAR 길이 포함).
   *
   * <p>{@link #createTable} 이 고아 회수 DROP 전에 부르고, "옛 것을 지우고 새로 만드는" 호출부(파이프라인 TEMP 재생성)도 지우기 전에 부른다.
   */
  public void validateNewColumns(List<DatasetColumnRequest> columns) {
    for (DatasetColumnRequest col : columns) {
      validateNewColumnName(col.columnName());
      // 타입 오류(알 수 없는 타입·VARCHAR 길이 범위)도 DDL 조립 전에 낸다 — 결과는 버리고 검사만 한다.
      mapDataType(col.dataType(), col.maxLength());
    }
  }

  /**
   * REPLACE 적재용 임시 테이블 이름 — {@code rtmp_} + 원본 이름의 MD5(32자 hex) = 항상 37자.
   *
   * <p>원본 이름에 접미사를 붙이지 않는 이유: {@code <table>_tmp} 는 긴 테이블에서 63바이트로 잘려 원본 이름 자체가 될 수 있고(63자면 정리 DROP
   * 이 원본을 지운다), 실제 데이터셋 {@code orders_tmp} 가 있으면 {@code orders} 의 REPLACE 가 그것을 지운다. 고정 길이 예약 접두어
   * 이름은 어느 쪽과도 겹치지 않고, 시퀀스 {@code <tmp>_id_seq}(44자)까지 잘리지 않는다. 생성·맞바꿈·정리·적재 대상 계산이 모두 이 메서드만 쓴다.
   */
  public static String tempTableName(String tableName) {
    return TEMP_TABLE_PREFIX
        + DigestUtils.md5DigestAsHex(tableName.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 시스템이 쓰는 테이블 이름 공간을 사용자 테이블 이름에서 예약한다 — 행 검색 색인({@code fh_search_{datasetId}})과 REPLACE 임시
   * 테이블({@link #TEMP_TABLE_PREFIX}).
   */
  private void rejectReservedTableName(String tableName) {
    for (String prefix : List.of(IndexRef.TABLE_PREFIX, TEMP_TABLE_PREFIX)) {
      if (tableName.startsWith(prefix)) {
        throw new InvalidTableNameException("'" + prefix + "' 로 시작하는 이름은 시스템 예약어입니다: " + tableName);
      }
    }
  }

  public void createTable(String tableName, List<DatasetColumnRequest> columns) {
    // 검증은 전부 백엔드 DDL(고아 회수 DROP 포함)보다 먼저다 — 실패할 요청이 DROP 을 일으키지 않게 한다.
    validateNewTableName(tableName);
    validateNewColumns(columns);
    resolver.tableStore().createTable(tableName, columns);
  }

  public void addColumn(String tableName, DatasetColumnRequest column) {
    // 테이블은 기존 이름이라 형식만, 컬럼은 새 이름이라 생성과 같은 검증(예약어·길이·타입)을 한다.
    validateName(tableName);
    validateNewColumns(List.of(column));
    resolver.tableStore().addColumn(tableName, column);
  }

  public void setColumnIndex(String tableName, String columnName, boolean indexed) {
    setColumnIndex(tableName, columnName, indexed, null);
  }

  public void setColumnIndex(
      String tableName, String columnName, boolean indexed, String columnType) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    resolver.tableStore().setColumnIndex(tableName, columnName, indexed, columnType);
  }

  public void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String pkCol : pkColumnNames) {
      DataTableNames.validate(pkCol);
    }
    resolver.tableStore().recreatePrimaryKeyIndex(tableName, pkColumnNames);
  }

  public void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    for (String pkCol : pkColumnNames) {
      DataTableNames.validate(pkCol);
    }
    resolver.tableStore().createPrimaryKeyIndexConcurrently(tableName, pkColumnNames);
  }

  public void dropTable(String tableName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    resolver.tableStore().dropTable(tableName);
  }

  /** REPLACE 적재용 임시(staging) 테이블 {@link #tempTableName} 을 만든다. */
  public void createTempTable(String tableName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    resolver.tableStore().createTempTable(tableName);
  }

  /**
   * REPLACE 적재를 마무리한다 — <b>쓴 행이 있을 때만</b> 맞바꾸고, 없으면 임시 테이블만 버린다.
   *
   * <p>"빈 결과는 기존 데이터를 파괴하지 않는다"는 제품 결정이다. 이 판단이 실행기마다 흩어져 있던 탓에 네 호출부 중 둘이 무조건 {@link #swapTable} 을
   * 불렀고, 2026-09-18 운영에서 AI 분류가 전량 실패해 0행이 나오자 빈 임시 테이블이 원본을 덮어 10건이 사라졌다(#685). API_CALL 경로 둘은 같은
   * 결함이 아직 터지지 않은 상태였다 — API 가 빈 페이지를 돌려주면 똑같이 지워진다.
   *
   * <p>정책을 {@code swapTable} 안에 숨기지 않는 이유: 그러면 "swap" 이라는 이름이 거짓말이 되고, DDL 원시연산 안에 제품 결정이 묻힌다. 결정은
   * 이름이 있는 자리에 둔다.
   *
   * <p>부분 실패(일부 배치만 성공)는 여기서 구분하지 않는다 — 성공한 행이 있으면 맞바꾼다. 그것이 {@code onError=CONTINUE} 를 고른 쪽의 선택이다.
   *
   * @param rowsWritten 임시 테이블에 실제로 적재된 행 수
   */
  public void finishReplace(String tableName, long rowsWritten) {
    resolver.tableStore().finishReplace(tableName, rowsWritten);
  }

  public void swapTable(String tableName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    resolver.tableStore().swapTable(tableName);
  }

  /** 임시(staging) 테이블이 있으면 버린다. 실패 시 원본 데이터를 보존하기 위해 호출한다. */
  public void dropTempTable(String tableName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    resolver.tableStore().dropTempTable(tableName);
  }

  public void renameColumn(String tableName, String oldName, String newName) {
    validateName(tableName);
    validateName(oldName);
    // 새 컬럼명 — 생성과 같은 길이 상한을 본다.
    validateNewColumnName(newName);
    resolver.tableStore().renameColumn(tableName, oldName, newName);
  }

  public void renameIndex(String tableName, String oldColName, String newColName) {
    validateName(tableName);
    validateName(oldColName);
    // 새 컬럼명 — renameColumn 보다 먼저 불리므로 여기서도 상한을 봐 인덱스만 바뀐 채 실패하지 않게 한다.
    validateNewColumnName(newColName);
    resolver.tableStore().renameIndex(tableName, oldColName, newColName);
  }

  public void alterColumnType(
      String tableName, String columnName, String dataType, Integer maxLength) {
    alterColumnType(tableName, columnName, dataType, maxLength, null);
  }

  public void alterColumnType(
      String tableName,
      String columnName,
      String dataType,
      Integer maxLength,
      String currentDataType) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    resolver
        .tableStore()
        .alterColumnType(tableName, columnName, dataType, maxLength, currentDataType);
  }

  public void setColumnNullable(String tableName, String columnName, boolean nullable) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    resolver.tableStore().setColumnNullable(tableName, columnName, nullable);
  }

  public void dropColumn(String tableName, String columnName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    resolver.tableStore().dropColumn(tableName, columnName);
  }

  public void cloneTable(
      String sourceTable,
      String targetTable,
      List<String> userColumns,
      List<DatasetColumnResponse> columnDefs) {
    validateName(sourceTable);
    // 복제본 테이블명만 새 이름이다 — 원본·컬럼은 기존 이름이라 형식만 본다.
    validateNewTableName(targetTable);
    for (String col : userColumns) {
      validateName(col);
    }
    resolver.tableStore().cloneTable(sourceTable, targetTable, userColumns, columnDefs);
  }

  public List<ColumnStatsResponse> getColumnStats(
      String tableName, List<DatasetColumnResponse> columns) {
    DataTableNames.validate(tableName);
    return resolver.tableStore().getColumnStats(tableName, columns);
  }
}
