package com.smartfirehub.auth.repository;

/**
 * 로그인 실패 카운터(login_attempts 테이블) 저장·조회 리포지토리(#144).
 *
 * <p>구현은 {@code JooqLoginAttemptRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface LoginAttemptRepository {

  /** 실패 카운터를 원자적으로 1 증가시키고 증가 후 값을 반환한다. 호출자 트랜잭션에 참여한다(WD-552, 구현체 주석 참고). */
  int incrementAttempts(String username);

  /** 만료되지 않은 시도 카운터. row가 없거나 만료됐으면 0. */
  int getAttempts(String username);

  /** 성공 로그인 시 카운터 제거. 호출자 트랜잭션에 참여한다(WD-552, 구현체 주석 참고). */
  void clear(String username);

  /** 만료된 row를 일괄 삭제하고 삭제 건수를 반환한다(스케줄러용). */
  int deleteExpired();
}
