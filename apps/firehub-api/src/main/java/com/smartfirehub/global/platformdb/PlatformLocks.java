package com.smartfirehub.global.platformdb;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 플랫폼 DB 의 advisory 잠금 추상화(WD-486).
 *
 * <p>왜: 가입·등급 정의 변경·체인 그래프 검사·파이프라인 잠금 등 여러 곳이 "같은 키로 동시에 들어오지 못하게" 막는 데 PostgreSQL 의 {@code
 * pg_advisory_*} 함수를 직접 불렀다. 플랫폼 DB 를 다른 DBMS 로 옮길 수 있게 이 호출을 인터페이스 뒤로 모으고, PG 구현은 {@code
 * postgres.PostgresPlatformLocks} 가 맡는다. 호출자의 키 문자열·잠금 범위는 바뀌지 않는다(동작 0 변경).
 *
 * <p>트랜잭션 범위 잠금은 "현재 Spring 트랜잭션(주입된 메인 DSL)에서 잡는다"를 계약으로 하며 시그니처에 jOOQ 타입을 노출하지 않는다. 세션 범위 잠금만
 * 호출자가 넘긴 {@link Connection}(autocommit 전용)에서 잡는다.
 */
public interface PlatformLocks {

  /**
   * 현재 Spring 트랜잭션에서 문자열 키로 트랜잭션 범위 잠금을 건다. 커밋·롤백 때 풀린다. 트랜잭션 밖에서 부르면 문장이 끝나자마자 풀려 아무것도 막지 못하므로 호출
   * 금지(PG 기준).
   */
  void xactLock(String key);

  /** 현재 Spring 트랜잭션에서 정수 키로 트랜잭션 범위 잠금을 건다. 문자열 키와 해시 공간이 달라 서로 섞이지 않는다(PG 기준). */
  void xactLock(long key);

  /**
   * autocommit 전용 커넥션에서 세션 범위 시도형 잠금을 시도한다. 기다리지 않는다.
   *
   * <p>절대 블록·대기하면 안 된다 — 호출자는 재시도 간격을 두고 폴링하며 시도 사이에 커넥션을 풀에 돌려준다. 여기서 기다리면 대기자들이 메인 풀 커넥션을 쥔 채
   * 멈추고, 잠금 보유자는 작업을 마칠 커넥션을 못 얻어 교착이 된다. 장차 Tibero DBMS_LOCK 구현을 포함해 모든 구현은 즉시 반환해야 한다.
   *
   * @return 잠금을 잡았으면 true, 다른 세션이 쥐고 있으면 false
   */
  boolean trySessionLock(Connection connection, String key) throws SQLException;

  /** 그 커넥션 세션이 쥔 세션 잠금을 모두 푼다. */
  void releaseAllSessionLocks(Connection connection) throws SQLException;
}
