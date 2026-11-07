package com.smartfirehub.global.platformdb;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 세션 범위 advisory 잠금의 시도·해제 계약(WD-493).
 *
 * <p>왜 분리했나: 세션 잠금은 "어느 DB 의 잠금이냐"가 호출자마다 다르다 — 증분 스텝 잠금(WD-223)은 플랫폼 DB 에서, 출력 테이블 잠금(#735)은 짝인
 * {@code SqlScriptExecutor.OUTPUT_LOCK_SQL} 과 같은 DB(데이터 저장소)에서 잡아야 서로를 막는다. 분리 모드(데이터 저장소가 별도 PG)에서
 * 두 DB 가 갈라지므로, 세션 잠금만 이 인터페이스로 뽑아 {@link PlatformLocks}(플랫폼 DB) 구현과 데이터 저장소 구현이 각자 만족하게 한다.
 */
public interface SessionLocks {

  /**
   * autocommit 전용 커넥션에서 세션 범위 시도형 잠금을 시도한다. 기다리지 않는다.
   *
   * <p>절대 블록·대기하면 안 된다 — 호출자는 재시도 간격을 두고 폴링하며 시도 사이에 커넥션을 풀에 돌려준다. 여기서 기다리면 대기자들이 메인 풀 커넥션을 쥔 채
   * 멈추고, 잠금 보유자는 작업을 마칠 커넥션을 못 얻어 교착이 된다. 장차 Tibero DBMS_LOCK 구현을 포함해 모든 구현은 즉시 반환해야 한다.
   *
   * @return 잠금을 잡았으면 true, 다른 세션이 쥐고 있으면 false
   */
  boolean trySessionLock(Connection connection, String key) throws SQLException;

  /**
   * 그 커넥션 세션이 쥔 그 키의 세션 잠금을 푼다. 쥐지 않은 키도 예외 없이 끝나야 한다(획득 시도가 중간에 끊겨 잠금 여부가 모호한 경우를 덮는다). 구현은 더 많이
   * 풀어도 된다(PG 는 {@code unlock_all}).
   */
  void releaseSessionLock(Connection connection, String key) throws SQLException;
}
