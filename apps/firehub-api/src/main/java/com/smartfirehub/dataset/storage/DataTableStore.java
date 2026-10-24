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

  /**
   * 테이블을 만든다. 이름·컬럼 정의가 잘못되면 예외를 던진다. 호출자 트랜잭션에 참여한다.
   *
   * <p>계약: 같은 이름의 기존 테이블(고아 테이블)이 있으면 먼저 지운다. 이어서 시스템 컬럼과 사용자 컬럼으로 테이블을 만들고, 인덱스 지정 컬럼의 인덱스와 PK 컬럼
   * 기준 유니크 인덱스를 만들며, {@value #UPDATED_AT_COLUMN} 갱신 트리거와 인덱스를 설치한다. 마지막으로 Python 읽기 슬롯 GRANT 를 커밋
   * 후에 맞추도록 예약한다.
   */
  void createTable(String tableName, List<DatasetColumnRequest> columns);

  /** 기존 테이블에 컬럼을 추가한다. 호출자 트랜잭션에 참여한다. */
  void addColumn(String tableName, DatasetColumnRequest column);

  /** 컬럼 인덱스를 만들거나 지운다. {@code columnType} 은 인덱스 종류 판단용(없으면 null). 호출자 트랜잭션에 참여한다. */
  void setColumnIndex(String tableName, String columnName, boolean indexed, String columnType);

  /**
   * PK 컬럼 기준 유니크 인덱스를 다시 만든다 — 그 테이블의 기존 PK 유니크 인덱스를 <b>이름이 아니라 카탈로그로</b> 모두 찾아 지운 뒤(REPLACE 맞바꿈이
   * 이름을 바꿔 놓은 것·옛 PK 잔존분 포함, WD-103) PK 컬럼이 있으면 하나 만든다. 시스템 PK({@code id})는 건드리지 않는다. 호출자 트랜잭션에
   * 참여한다.
   */
  void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames);

  /** PK 유니크 인덱스를 잠금 없이(CONCURRENTLY) 만든다. 트랜잭션 밖에서 호출해야 한다. */
  void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames);

  /** 테이블을 지운다. 호출자 트랜잭션에 참여한다. */
  void dropTable(String tableName);

  /**
   * REPLACE 적재용 임시(staging) 테이블을 이번 실행 전용 이름({@code DataTableService.newTempTableName})으로 만들고 그 이름을
   * 돌려준다 (WD-151). 같은 테이블에 겹친 실행이 서로의 임시 테이블을 만들거나 지우지 않게 한다.
   */
  String createTempTable(String tableName);

  /**
   * REPLACE 적재를 마무리한다 — <b>쓴 행이 있을 때만</b> 맞바꾸고, 없으면 임시 테이블만 버린다.
   *
   * <p>"빈 결과는 기존 데이터를 파괴하지 않는다"는 제품 결정(#685)이다. 이 판단이 실행기마다 흩어져 있던 탓에 호출부 중 일부가 무조건 {@link
   * #swapTable} 을 불렀고, 2026-09-18 운영에서 AI 분류가 전량 실패해 0행이 나오자 빈 임시 테이블이 원본을 덮어 10건이 사라졌다. 정책을 {@code
   * swapTable} 안에 숨기면 "swap" 이름이 거짓말이 되므로 이름이 있는 이 자리에 둔다.
   *
   * <p>백엔드 중립 규칙이므로 {@code default} 로 제공한다. 구현체는 이 메서드를 재정의해 무조건 맞바꾸면 안 된다 — {@link
   * #swapTable}·{@link #dropTempTable} 만 구현한다.
   *
   * <p>부분 실패(일부 배치만 성공)는 구분하지 않는다 — 성공한 행이 있으면 맞바꾼다. 그것이 {@code onError=CONTINUE} 를 고른 쪽의 선택이다.
   *
   * @param tempName {@link #createTempTable} 이 돌려준 이번 실행의 임시 테이블 이름
   * @param rowsWritten 임시 테이블에 실제로 적재된 행 수
   */
  default void finishReplace(String tableName, String tempName, long rowsWritten) {
    if (rowsWritten > 0) {
      swapTable(tableName, tempName);
    } else {
      dropTempTable(tableName, tempName);
    }
  }

  /**
   * APPEND 적재를 마무리한다(WD-77) — 이번 실행의 임시(staging) 테이블에 쌓인 행을 <b>원본을 비우지 않고</b> 원본에 옮기고 임시 테이블을 버린다.
   * 쓴 행이 없으면 버리기만 한다.
   *
   * <p>PG 의 APPEND 는 출력 테이블에 직접 적재하므로 이 메서드를 쓰지 않는다 — 기본 구현은 실수로 불리면 즉시 드러나도록 예외를 던진다. 임시 테이블을 경유해야
   * 하는 백엔드(Tibero: 적재 실행기가 스테이징에만 쓴다)만 재정의한다.
   *
   * @param tempName {@link #createTempTable} 이 돌려준 이번 실행의 임시 테이블 이름
   * @param rowsWritten 임시 테이블에 실제로 적재된 행 수
   */
  default void finishAppend(String tableName, String tempName, long rowsWritten) {
    throw new UnsupportedOperationException("이 저장소는 임시 테이블 경유 APPEND 를 쓰지 않습니다: " + backend());
  }

  /**
   * 사용자가 직접 쓴 INSERT·UPDATE 가 기대는 행 트리거(ID·_UPDATED_AT)를 쓰기 전에 보장한다(WD-77). 트리거가 필요 없는 백엔드(PG:
   * 기본값·트리거가 테이블 생성 때 이미 있다)는 아무것도 하지 않는다 — 그래서 호출자가 백엔드를 가리지 않고 부를 수 있다.
   *
   * @throws DataStoreFeatureUnsupportedException 트리거가 필요한데 없고 만들 권한도 없을 때(Tibero)
   */
  default void ensureRowTrigger(String tableName) {}

  /**
   * 임시 테이블 이름이 이 백엔드·테이블의 것인지 확인한다(아니면 {@code InvalidTableNameException}). 기본은 PG 규칙 — {@code
   * DataTableService#requireTempTableOf}. 임시 테이블 이름 규칙이 다른 백엔드(Tibero 의 {@code STG_<24hex>})가
   * 재정의한다.
   */
  default void requireTempTable(String tableName, String tempName) {
    com.smartfirehub.dataset.service.DataTableService.requireTempTableOf(tableName, tempName);
  }

  /**
   * 원본을 이번 실행의 임시 테이블({@code tempName})로 맞바꾼다. 원본 삭제와 리네임을 구현체 자체의 트랜잭션 블록 안에서 원자적으로 실행한다(호출자 트랜잭션이
   * 있으면 그 안에 중첩된다). 구현체는 {@code tempName} 이 그 테이블의 임시 테이블 이름인지 확인해야 한다.
   */
  void swapTable(String tableName, String tempName);

  /** 이번 실행의 임시(staging) 테이블({@code tempName})이 있으면 버린다. 실패 시 원본 보존용. 다른 실행의 임시 테이블은 건드리지 않는다. */
  void dropTempTable(String tableName, String tempName);

  /**
   * 열 구성이 바뀐 러너 TEMP 를 다시 만들기 전에 기존 테이블을 지우지 않고 보관본 이름({@code
   * DataTableService.rebuildBackupTableName})으로 옮긴다(WD-238). 호출자 트랜잭션에 참여한다 — 같은 트랜잭션에서 원래 이름으로 새
   * 테이블을 만든다. 스텝이 성공하면 {@link #dropRebuildBackup}, 실패하면 {@link #restoreRebuildBackup} 을 부른다.
   *
   * <p>기본 구현은 지원하지 않음. Tibero 는 WD-77 2단계부터 러너 TEMP 를 만들지만 재생성 보관은 아직 지원하지 않아, 열 구성이 바뀐 재실행을
   * 한국어 안내로 거부하고 보관본 정리({@link #dropRebuildBackup})는 「없음」으로 답한다({@code TiberoDataTableStore}).
   *
   * @return 옮긴 테이블의 OID — 되돌리기·버리기가 「이 실행이 옮긴 그 테이블」인지 확인하는 데 쓴다(같은 TEMP 를 겹쳐 다시 만든 다른 실행의 보관본을
   *     건드리지 않게)
   */
  default long moveToRebuildBackup(String tableName, String backupName) {
    throw new UnsupportedOperationException("이 저장소는 임시 데이터셋 재생성 보관을 지원하지 않습니다");
  }

  /**
   * 실패한 재생성을 되돌린다(WD-238) — 원래 이름의 새 테이블을 지우고 보관본을 원래 이름으로 되돌린다. 보관본이 없으면 새 테이블을 지우지 않고 예외를 던진다(둘 다
   * 잃지 않게). 보관본 OID 가 {@code expectedOid} 와 다르면(다른 실행이 옮긴 것) 역시 아무것도 바꾸지 않고 예외를 던진다.
   */
  default void restoreRebuildBackup(String tableName, String backupName, long expectedOid) {
    throw new UnsupportedOperationException("이 저장소는 임시 데이터셋 재생성 보관을 지원하지 않습니다");
  }

  /**
   * 재생성 보관본이 있으면 지우고, 지웠는지 돌려준다(WD-238). 스텝 성공 뒤·남은 보관본 정리에 쓴다. {@code expectedOid} 가 있으면 그 OID 의
   * 보관본만 지운다(다르면 지우지 않고 false) — null 이면 남은 보관본을 무조건 지운다(재생성 직전·데이터셋 삭제).
   */
  default boolean dropRebuildBackup(String tableName, String backupName, Long expectedOid) {
    throw new UnsupportedOperationException("이 저장소는 임시 데이터셋 재생성 보관을 지원하지 않습니다");
  }

  /** 컬럼 이름을 바꾼다. 호출자 트랜잭션에 참여한다. */
  void renameColumn(String tableName, String oldName, String newName);

  /**
   * 컬럼명 변경에 맞춰 해당 컬럼의 필드 인덱스 이름을 바꾼다. {@link #renameColumn} <b>전에</b> 부른다 — 구현은 옛 컬럼 이름으로 인덱스를 찾는다.
   * 인덱스가 없으면 아무것도 하지 않고, 새 이름이 다른 객체와 겹치면 오류 대신 이름을 그대로 둔다(WD-100).
   */
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
