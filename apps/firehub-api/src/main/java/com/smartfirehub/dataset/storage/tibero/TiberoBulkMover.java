package com.smartfirehub.dataset.storage.tibero;

import java.util.List;

/**
 * Tibero "스테이징 → 출력 테이블" 옮기기 창구(WD-77). 옮기기(비우기 + 옮기기 한 트랜잭션)는 이 인터페이스 뒤의 db-executor 에 맡긴다({@code
 * DbExecutorBulkMover}). 저장소 구현({@link TiberoDataTableStore})이 이 인터페이스만 알아 테스트에서 가짜로 바꿀 수 있다.
 *
 * <p>스테이징 <b>적재</b>는 이 창구를 지나지 않는다 — api 로컬 경로는 {@code DataRowStore#insertTempBatch}(판단 8), 실행기 경로는
 * Python 실행기 {@code RemoteLoader} 가 db-executor {@code /execute/load} 로 직접 보낸다.
 *
 * <p>모든 메서드는 실패하면 예외를 던진다(호출자가 스테이징을 지우고 예외를 전파한다).
 */
public interface TiberoBulkMover {

  /**
   * 출력 테이블을 비우고 스테이징 행을 모두 옮긴다 — 한 Tibero 트랜잭션(비우기 + 옮기기 + 커밋). 실패하면 롤백되어 출력 테이블은 그대로다.
   *
   * <p>비우기 선행문은 실행기가 그 테이블에 {@code LOCK TABLE … IN EXCLUSIVE MODE} 를 먼저 걸고 실행하므로, 같은 출력에 대한
   * REPLACE·APPEND 가 겹쳐도 한쪽이 끝난 뒤에야 다른 쪽이 진행한다.
   *
   * <p>주의: 출력 테이블에 PK 가 있고 스테이징에 같은 키가 중복되면 Tibero 에서는 옮기기 전체가 실패한다(스테이징에는 PK 가 없고, {@code
   * onError=CONTINUE} 로도 일부 배치만 건질 수 없다 — 한 문장·한 트랜잭션이다).
   *
   * @param tenantId 현재 테넌트(호출 컨텍스트의 테넌트와 같아야 한다)
   * @param schema 대문자 Tibero 스키마
   * @param table 출력 테이블(메타데이터 이름)
   * @param staging 스테이징 물리 이름({@code STG_<24 대문자 16진>})
   * @param columns 옮길 사용자 컬럼
   */
  void moveReplace(
      long tenantId, String schema, String table, String staging, List<String> columns);

  /** 출력 테이블을 비우지 않고 스테이징 행을 옮긴다(APPEND). 인자는 {@link #moveReplace} 와 같다. */
  void moveAppend(long tenantId, String schema, String table, String staging, List<String> columns);
}
