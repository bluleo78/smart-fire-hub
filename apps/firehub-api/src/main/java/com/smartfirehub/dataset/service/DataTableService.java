package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.dataset.storage.DataTableStore;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 테이블 DDL 파사드 — 테넌트 저장소 백엔드에 위임(WD-72).
 *
 * <p>호출부는 이 클래스만 알면 된다. 실제 SQL 은 {@link DataStoreResolver} 가 현재 테넌트 기준으로 고른 {@link DataTableStore}
 * 구현이 실행하며, 호출자의 트랜잭션에 그대로 참여한다. 이름 검증({@link #validateName})처럼 백엔드와 무관한 순수 검증과, 기본 인자만 채워 다른 오버로드로
 * 넘기는 편의 오버로드는 여기에 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTableService {

  private final DataStoreResolver resolver;

  /**
   * 새로 만드는 테이블명의 최대 길이(WD-95).
   *
   * <p>PostgreSQL 은 식별자를 63바이트에서 조용히 자른다({@link DataTableNames} 이 ASCII 만 허용하므로 글자 수 = 바이트 수). 상한이
   * 없으면 앞 63자가 같은 두 이름이 같은 물리 테이블이 돼 고아 회수 DROP 이 남의 데이터를 지우고, 63자 넘는 컬럼명은 조회 결과 키가 메타데이터와 어긋난다.
   * 테이블은 63 이 아니라 56 인데, 이름이 V125 와 같은 규약으로 고정된 {@code ix_<table>_upd}(+7)까지 63 안에 들어와야 하기 때문이다.
   */
  public static final int MAX_TABLE_NAME_LENGTH = 56;

  /** 새로 만드는 컬럼명의 최대 길이 — 근거는 {@link #MAX_TABLE_NAME_LENGTH}. */
  public static final int MAX_COLUMN_NAME_LENGTH = 63;

  /**
   * REPLACE 적재용 임시 테이블 접두어 — 사용자 테이블명으로는 예약한다({@link #rejectReservedTableName}). 이름 조립은 {@link
   * #newTempTableName}(실행마다 새 이름)과 {@link #legacyTempTableName}(옛 고정 이름 정리용) 단독.
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
      throw new InvalidTableNameException(reservedColumnNameMessage(columnName));
    }
    validateName(columnName);
    if (columnName.length() > MAX_COLUMN_NAME_LENGTH) {
      throw new InvalidTableNameException(
          "필드명은 최대 " + MAX_COLUMN_NAME_LENGTH + "자까지 쓸 수 있습니다(입력 " + columnName.length() + "자)");
    }
  }

  /** 시스템 예약 컬럼명 안내 문구 — 데이터셋 컬럼 생성과 파이프라인 스텝 저장 검증(WD-154)이 같은 문장을 쓰도록 한 곳에 둔다. */
  public static String reservedColumnNameMessage(String columnName) {
    return "컬럼명 '" + columnName + "'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)";
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

  /** 실행마다 다른 임시 테이블 이름의 난수 부분 길이(hex 글자 수, 48비트). */
  static final int TEMP_TABLE_RANDOM_HEX = 12;

  /** 임시 테이블 이름의 난수 부분 생성기 — 보안 용도는 아니지만 스레드 안전하고 예측이 어렵다. */
  private static final SecureRandom TEMP_TABLE_RANDOM = new SecureRandom();

  /**
   * REPLACE 적재 <b>한 번</b>이 쓸 새 임시 테이블 이름 — {@code rtmp_<난수 12 hex>_<원본 이름의 MD5 32 hex>} = 항상
   * 50자(WD-151).
   *
   * <p><b>왜 실행마다 다른가.</b> 예전에는 출력마다 고정된 이름({@link #legacyTempTableName})을 DROP IF EXISTS 후 다시 만들었다.
   * 같은 출력에 REPLACE 두 건이 겹치면 동시 CREATE 가 {@code pg_type_typname_nsp_index} 중복으로 깨지거나, 뒤 실행이 앞 실행의 임시
   * 테이블을 지우고 다시 만들었고, 실패한 실행의 정리(DROP)가 아직 적재 중인 다른 실행의 임시 테이블을 지웠다. 이름이 실행마다 다르면 생성·적재·정리가 서로 닿지
   * 않는다. 맞바꿈은 한 트랜잭션(원본 DROP + RENAME)이고 PostgreSQL 의 ACCESS EXCLUSIVE 잠금이 겹친 맞바꿈을 차례로 세우므로, 둘 다
   * 성공하고 나중에 끝난 실행이 이긴다(REPLACE 의 의미 그대로).
   *
   * <p><b>왜 난수가 MD5 앞에 있는가.</b> {@code LIKE ... INCLUDING ALL} 은 인덱스 이름을 PostgreSQL 기본 규칙({@code
   * <테이블>_<컬럼>_idx})으로 새로 짓는데, 63바이트를 넘으면 긴 쪽의 <b>끝</b>부터 자른다. 난수가 끝에 있으면 컬럼명이 20자 남짓만 돼도 난수가 잘려 두
   * 실행의 인덱스 이름이 같아지고, 동시에 만들면 같은 종류의 중복 오류가 다시 난다. 앞에 두면 잘리는 것은 MD5 쪽이고 난수는 남는다.
   *
   * <p><b>길이.</b> 50자 — 시퀀스 {@code <tmp>_id_seq}(57자), 시스템 PK {@code <tmp>_pkey}(55자)까지 63바이트 안이라
   * 잘리지 않는다. 원본 이름에 접미사를 붙이지 않는 이유는 예전과 같다: {@code <table>_tmp} 는 긴 테이블에서 원본 이름 자체로 잘릴 수 있고 실제 데이터셋
   * 이름과 겹칠 수 있다. 사용자 테이블은 {@code rtmp_} 로 시작할 수 없으므로({@link #rejectReservedTableName}) 어떤 데이터셋과도
   * 겹치지 않는다.
   *
   * <p><b>남는 임시 테이블.</b> 실행이 맞바꿈·정리 전에 죽으면(프로세스 종료 등) 그 실행의 임시 테이블이 남는다. 이름이 실행마다 달라 다른 실행이 지울 수 없다
   * — 살아 있는 실행의 것인지 알 방법이 없어 자동으로 쓸지 않는다. 데이터셋이 아니고 SQL 스텝에서 참조할 수도 없어(예약 접두어) 디스크만 차지한다. 확인된 실행이
   * 없을 때 운영자가 {@code SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   * WHERE n.nspname = '<데이터 스키마>' AND c.relkind = 'r' AND c.relname LIKE 'rtmp\_%'} 로 찾아 DROP
   * 한다(시퀀스는 테이블 소유라 함께 지워진다).
   */
  public static String newTempTableName(String tableName) {
    byte[] random = new byte[TEMP_TABLE_RANDOM_HEX / 2];
    TEMP_TABLE_RANDOM.nextBytes(random);
    return TEMP_TABLE_PREFIX
        + HexFormat.of().formatHex(random)
        + "_"
        + DataTableNames.md5Hex(tableName);
  }

  /**
   * WD-151 이전의 출력별 고정 임시 테이블 이름 {@code rtmp_<md5>}(37자). 새 실행은 이 이름을 쓰지 않는다 — 옛 버전이 남긴 잔재를 {@code
   * createTempTable} 이 지우는 데만 쓴다(새 코드에서는 살아 있는 실행이 이 이름을 쓸 수 없으므로 지워도 안전하다).
   */
  public static String legacyTempTableName(String tableName) {
    return TEMP_TABLE_PREFIX + DataTableNames.md5Hex(tableName);
  }

  /**
   * {@code tempName} 이 {@code tableName} 의 REPLACE 임시 테이블 이름({@link #newTempTableName} 모양)인지 확인한다 —
   * 아니면 {@link InvalidTableNameException}. 맞바꿈(원본 DROP 후 RENAME)과 정리(DROP)는 받은 이름을 그대로 쓰므로, 인자가
   * 뒤바뀌거나 잘못 넘어와도 실제 데이터셋 테이블이나 다른 출력의 임시 테이블을 지우지 않게 하는 마지막 관문이다. 정확한 모양({@code rtmp_<12
   * hex>_<md5(tableName)>})만 통과한다.
   */
  public static void requireTempTableOf(String tableName, String tempName) {
    String expected =
        "^"
            + TEMP_TABLE_PREFIX
            + "[0-9a-f]{"
            + TEMP_TABLE_RANDOM_HEX
            + "}_"
            + DataTableNames.md5Hex(tableName)
            + "$";
    if (tempName == null || !tempName.matches(expected)) {
      throw new InvalidTableNameException(
          "'" + tempName + "' 은(는) 테이블 '" + tableName + "' 의 REPLACE 임시 테이블 이름이 아닙니다");
    }
  }

  /** 러너 TEMP 재생성 보관본 이름 접두(WD-238) — 예약 접두 {@link #TEMP_TABLE_PREFIX} 아래라 사용자 테이블과 겹치지 않는다. */
  public static final String REBUILD_BACKUP_PREFIX = TEMP_TABLE_PREFIX + "bak_";

  /**
   * 열 구성이 바뀐 러너 TEMP 를 다시 만드는 동안 옛 테이블을 보관할 이름 — {@code rtmp_bak_<원본 이름의 MD5 32 hex>}(41자, WD-238).
   *
   * <p><b>왜 원본마다 고정인가.</b> 서버가 스텝 도중 죽어 보관본이 남으면(되돌리기·정리 전) 다음 재생성이 이 이름으로 찾아 지운다 — 난수 이름이면 찾을 방법이
   * 없다. 같은 TEMP 를 동시에 다시 만드는 두 실행은 뒤 실행이 남은 보관본을 지우므로 앞 실행의 되돌리기는 실패를 로그로만 남긴다(겹친 재생성은 원래도 나중 실행이
   * 이긴다). REPLACE 임시 테이블({@link #newTempTableName}·{@link #legacyTempTableName})과는 모양이 달라 {@link
   * #requireTempTableOf} 를 통과하지 못하고, REPLACE 의 옛 이름 잔재 정리도 이 이름을 지우지 않는다.
   *
   * <p>운영자 수동 확인: {@code SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid =
   * c.relnamespace WHERE n.nspname = '<데이터 스키마>' AND c.relkind = 'r' AND c.relname LIKE
   * 'rtmp\_bak\_%'} — 실행 중인 스텝이 없을 때 남은 것은 지워도 된다(데이터셋 메타는 이미 새 열 구성을 가리킨다).
   */
  public static String rebuildBackupTableName(String tableName) {
    return REBUILD_BACKUP_PREFIX + DataTableNames.md5Hex(tableName);
  }

  /** {@code backupName} 이 {@code tableName} 의 재생성 보관본 이름인지 확인한다 — 아니면 거부해 실제 테이블을 지우거나 덮지 않게 한다. */
  public static void requireRebuildBackupOf(String tableName, String backupName) {
    if (backupName == null || !backupName.equals(rebuildBackupTableName(tableName))) {
      throw new InvalidTableNameException(
          "'" + backupName + "' 은(는) 테이블 '" + tableName + "' 의 재생성 보관본 이름이 아닙니다");
    }
  }

  /**
   * 러너 TEMP 테이블을 보관본으로 옮기고 그 이름을 돌려준다(WD-238). 호출자 트랜잭션에 참여한다 — 같은 트랜잭션에서 원래 이름으로 새 테이블을 만든다. 남은
   * 보관본(서버가 도중에 죽은 이전 실행)은 먼저 지운다 — 데이터셋 메타가 이미 그 실행의 새 열 구성이라 되돌릴 근거가 없다.
   *
   * @return 옮긴 테이블의 OID(보관본 이름은 {@link #rebuildBackupTableName}) — 되돌리기·버리기에 그대로 넘긴다
   */
  public long moveToRebuildBackup(String tableName) {
    DataTableNames.validate(tableName);
    String backupName = rebuildBackupTableName(tableName);
    if (resolver.tableStore().dropRebuildBackup(tableName, backupName, null)) {
      log.warn("테이블 {} 의 남은 재생성 보관본 {} 을 지웠습니다(이전 실행이 마무리 전에 멈춘 흔적)", tableName, backupName);
    }
    return resolver.tableStore().moveToRebuildBackup(tableName, backupName);
  }

  /**
   * 실패한 재생성을 되돌린다 — 새 테이블을 지우고 보관본을 원래 이름으로 되돌린다(WD-238). 보관본이 없거나 이 실행이 옮긴 테이블(OID)이 아니면 아무것도 지우지
   * 않고 예외.
   */
  public void restoreRebuildBackup(String tableName, String backupName, long expectedOid) {
    DataTableNames.validate(tableName);
    requireRebuildBackupOf(tableName, backupName);
    resolver.tableStore().restoreRebuildBackup(tableName, backupName, expectedOid);
  }

  /** 재생성 보관본이 있으면 지우고 지웠는지 돌려준다(WD-238) — 스텝 성공 뒤(이 실행이 옮긴 OID 만), 데이터셋 삭제 때(null — 무조건) 쓴다. */
  public boolean dropRebuildBackup(String tableName, String backupName, Long expectedOid) {
    DataTableNames.validate(tableName);
    requireRebuildBackupOf(tableName, backupName);
    return resolver.tableStore().dropRebuildBackup(tableName, backupName, expectedOid);
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

  /**
   * REPLACE 적재용 임시(staging) 테이블을 <b>이번 실행 전용 이름</b>({@link #newTempTableName})으로 만들고 그 이름을
   * 돌려준다(WD-151). 호출자는 적재·{@link #finishReplace}·{@link #swapTable}·{@link #dropTempTable} 에 이 이름을
   * 그대로 넘긴다 — 이름을 다시 계산할 방법이 없다(난수).
   */
  public String createTempTable(String tableName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    return resolver.tableStore().createTempTable(tableName);
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
   * @param tempName {@link #createTempTable} 이 돌려준 이번 실행의 임시 테이블 이름
   * @param rowsWritten 임시 테이블에 실제로 적재된 행 수
   */
  public void finishReplace(String tableName, String tempName, long rowsWritten) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableStore store = resolver.tableStore();
    store.requireTempTable(tableName, tempName);
    store.finishReplace(tableName, tempName, rowsWritten);
  }

  /**
   * 사용자 INSERT·UPDATE·증분 대상 테이블들의 행 트리거를 쓰기 전에 보장한다(WD-77). PG 는 아무것도 하지 않는다(저장소 기본 구현). Tibero 는
   * 테이블 여럿을 한 접속에서 확인·생성하며, 트리거 권한이 없는 테넌트는 운영자 안내가 든 422 가 난다.
   */
  public void ensureRowTriggers(java.util.Collection<String> tableNames) {
    resolver.tableStore().ensureRowTriggers(tableNames);
  }

  /**
   * APPEND 적재를 마무리한다 — 임시 테이블의 행을 원본을 비우지 않고 옮기고 임시 테이블을 버린다(WD-77, Tibero 전용 경로). PG 는 출력 테이블에 직접
   * 적재하므로 이 메서드를 부르지 않는다(PG 저장소 구현은 예외를 던진다).
   */
  public void finishAppend(String tableName, String tempName, long rowsWritten) {
    DataTableNames.validate(tableName);
    DataTableStore store = resolver.tableStore();
    store.requireTempTable(tableName, tempName);
    store.finishAppend(tableName, tempName, rowsWritten);
  }

  /** 원본을 이번 실행의 임시 테이블({@code tempName})로 맞바꾼다. 이름이 그 테이블의 임시 테이블 모양이 아니면 거부한다. */
  public void swapTable(String tableName, String tempName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableStore store = resolver.tableStore();
    store.requireTempTable(tableName, tempName);
    store.swapTable(tableName, tempName);
  }

  /**
   * 이번 실행의 임시(staging) 테이블이 있으면 버린다. 실패 시 원본 데이터를 보존하기 위해 호출한다. 다른 실행의 임시 테이블은 이름이 달라 닿지 않는다. 이름이 그
   * 테이블의 임시 테이블 모양이 아니면 거부한다 — 실제 테이블을 지우는 일이 없게 한다.
   */
  public void dropTempTable(String tableName, String tempName) {
    // 테넌트 해석 전에 이름부터 검증한다(기존 동작 유지).
    DataTableNames.validate(tableName);
    DataTableStore store = resolver.tableStore();
    store.requireTempTable(tableName, tempName);
    store.dropTempTable(tableName, tempName);
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
