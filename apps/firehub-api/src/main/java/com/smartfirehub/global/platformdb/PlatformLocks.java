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

  /**
   * {@link #xactLock(String)} 의 시도형 — 현재 Spring 트랜잭션에서 같은 키 공간의 트랜잭션 범위 잠금을 <b>기다리지 않고</b> 시도한다.
   * 잡으면 커밋·롤백 때 풀린다.
   *
   * <p>왜(WD-644): 커밋 뒤 PYTHON 읽기 권한 동기화는 단일 실행기에서 돈다. 한 테넌트의 잠금을 기다리면 그 뒤에 줄 선 다른 테넌트의 동기화가 모두
   * 멈추므로, 그 경로만 시도형으로 잡고 바쁘면 나중에 다시 시도한다. 모든 구현은 즉시 반환해야 한다.
   *
   * <p>주의: PG 의 시도형은 다른 트랜잭션이 쥐고 있을 때뿐 아니라 <b>기다리는 트랜잭션이 줄에 있을 때도</b> 실패한다. 그래서 시도만 되풀이하면 잠금이 쉴 새
   * 없이 쓰이는 동안 끝내 잡지 못할 수 있다 — 그런 호출자는 몇 번 뒤 {@link #xactLock(String, Duration)} 으로 줄에 선다.
   *
   * @return 잡았으면 true, 다른 트랜잭션이 쥐고 있거나 기다리는 트랜잭션이 있으면 false
   */
  boolean tryXactLock(String key);

  /**
   * {@link #xactLock(String)} 과 같되 최대 {@code timeout} 만 기다린다(WD-644) — 잠금 대기 줄에 서므로 시도형처럼 굶지 않고, 대기
   * 시간은 묶인다. 대기 제한은 이 잠금 대기에만 걸리고, 잡은 뒤의 문장에는 남지 않는다.
   *
   * <p>시간을 넘기면 예외를 던진다 — PG 는 SQLState {@code 55P03}(lock_not_available)의 SQLException 이 원인 사슬에 있고
   * 현재 트랜잭션은 중단된다. Tibero({@code DBMS_LOCK})는 {@link PlatformLockWaitTimeoutException} 이다(WD-493
   * 이후, 트랜잭션은 깨지지 않지만 호출자는 같은 방식으로 트랜잭션을 끝낸다). 어느 쪽이든 호출자는 트랜잭션을 끝내고 다시 시도한다. 대기 길이의 단위는 구현마다 다르다 —
   * Tibero 는 초 단위 정수라 1초 미만은 1초로 올린다.
   */
  void xactLock(String key, Duration timeout);

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
