package com.smartfirehub.auth.repository;

/**
 * 로그인 IP 기준 제한용 실패 기록(login_ip_failures 테이블) 저장·조회 리포지토리(WD-578).
 *
 * <p>(IP 키, 계정) 쌍마다 행 하나를 두고 15분 뒤 만료한다. 한 IP 키에서 실패한 서로 다른 계정 수로 비밀번호 스프레이를 판정한다. 구현은 {@code
 * JooqLoginIpFailureRepository}(jOOQ/PostgreSQL) 이다.
 */
public interface LoginIpFailureRepository {

  /**
   * (IP 키, 계정) 실패를 기록한다. 같은 쌍이 이미 있으면 행을 늘리지 않고 만료만 15분 뒤로 미룬다(UPSERT). 호출자 트랜잭션에 참여한다(WD-552, 구현체
   * 주석 참고).
   */
  void recordFailure(String ipKey, String username);

  /** IP 키에서 만료되지 않은 실패 계정 수(서로 다른 계정). DB 시계로 만료를 판정한다. */
  int countActiveAccounts(String ipKey);

  /**
   * IP 키의 트랜잭션 범위 잠금을 잡는다 — 같은 키의 로그인 검사·실패 기록을 줄 세운다(WD-578). 잠금은 호출자 트랜잭션의 커밋·롤백 때 풀린다. 트랜잭션 밖에서
   * 부르면 문장 끝에 바로 풀려 아무것도 막지 못하므로 구현은 진행 중인 트랜잭션을 요구한다.
   */
  void lockKey(String ipKey);

  /** 만료된 행을 일괄 삭제하고 삭제 건수를 반환한다(스케줄러용). */
  int deleteExpired();
}
