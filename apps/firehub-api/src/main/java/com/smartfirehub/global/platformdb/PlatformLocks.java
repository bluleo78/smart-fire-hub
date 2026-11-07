package com.smartfirehub.global.platformdb;

import java.time.Duration;

/**
 * 플랫폼 DB 의 advisory 잠금 추상화(WD-486).
 *
 * <p>왜: 가입·등급 정의 변경·체인 그래프 검사·파이프라인 잠금 등 여러 곳이 "같은 키로 동시에 들어오지 못하게" 막는 데 PostgreSQL 의 {@code
 * pg_advisory_*} 함수를 직접 불렀다. 플랫폼 DB 를 다른 DBMS 로 옮길 수 있게 이 호출을 인터페이스 뒤로 모으고, PG 구현은 {@code
 * postgres.PostgresPlatformLocks} 가 맡는다. 호출자의 키 문자열·잠금 범위는 바뀌지 않는다(동작 0 변경).
 *
 * <p>트랜잭션 범위 잠금은 "현재 Spring 트랜잭션(주입된 메인 DSL)에서 잡는다"를 계약으로 하며 시그니처에 jOOQ 타입을 노출하지 않는다. 세션 범위 잠금만
 * 호출자가 넘긴 커넥션(autocommit 전용)에서 잡으며 그 계약은 {@link SessionLocks} 에 있다.
 */
public interface PlatformLocks extends SessionLocks {

  /**
   * 현재 Spring 트랜잭션에서 문자열 키로 트랜잭션 범위 잠금을 건다. 커밋·롤백 때 풀린다. 트랜잭션 밖에서 부르면 문장이 끝나자마자 풀려 아무것도 막지 못하므로 호출
   * 금지(PG 기준).
   */
  void xactLock(String key);

  /** 현재 Spring 트랜잭션에서 정수 키로 트랜잭션 범위 잠금을 건다. 문자열 키와 해시 공간이 달라 서로 섞이지 않는다(PG 기준). */
  void xactLock(long key);

  /**
   * {@link #xactLock(String)} 의 시도형 — 현재 Spring 트랜잭션에서 같은 키의 트랜잭션 범위 잠금을 최대 {@code maxWait} 동안
   * 시도한다(WD-624).
   *
   * <p>왜: 사용자 트랜잭션의 커밋 직전에 잠금을 잡아야 하는 경로(데이터셋 등급 좁히기)는 그 트랜잭션의 커넥션을 쥔 채 기다린다. 무한정 기다리면 동시 요청이 풀을 다
   * 쥔 채 멈출 수 있으므로 상한을 둔다. 실패해도 호출자 트랜잭션을 실패 상태로 만들면 안 된다(기다리는 문장의 시간 초과는 PG 트랜잭션을 깨뜨린다).
   *
   * @return 잡았으면 true(트랜잭션 끝까지 유지), 시간 안에 못 잡았거나 인터럽트되면 false
   */
  boolean tryXactLock(String key, Duration maxWait);
}
