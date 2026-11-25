package com.smartfirehub.global.platformdb;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 플랫폼 DB 세션에 활성 테넌트를 심는 포트(WD-486).
 *
 * <p>RLS 가 테넌트를 가르는 데 쓰는 세션 값을 DB 종류별 문법으로 설정하는 일을 인터페이스 뒤로 숨긴다. 호출자(트랜잭션 매니저, 로그인 경로)는 "테넌트를 심는다"만
 * 알고 DB 별 SQL 은 구현체가 안다.
 *
 * <p><b>계약 — 심은 값은 커넥션이 풀로 돌아가기 전에 사라져야 한다.</b> 그래야 풀에 반납된 커넥션이 다음 요청에 이전 테넌트를 물려주지 않는다. 두 길이 있다.
 *
 * <ul>
 *   <li>값 자체가 <b>트랜잭션-로컬</b>이라 커밋/롤백과 함께 사라진다(PG {@code set_config(..., true)}). 이때 {@link
 *       #clearConnection} 은 기본 빈 구현 그대로 둔다.
 *   <li>값이 <b>세션 범위</b>라 커밋/롤백 뒤에도 남는다(Tibero 컨텍스트, WD-492). 이때 구현체가 {@link #clearConnection} 으로
 *       지우고, 트랜잭션 매니저({@code TenantAwareTransactionManager.doCleanupAfterCompletion})가 커넥션을 풀에 돌려주기
 *       직전에 부른다. 지우기에 실패한 커넥션은 매니저가 풀에서 버린다.
 * </ul>
 *
 * <p>그래서 테넌트는 트랜잭션에 묶인 커넥션에만 심는다 — 트랜잭션 밖 커넥션에는 지워 줄 자리가 없다.
 */
public interface PlatformTenantSession {

  /**
   * 트랜잭션에 묶인 커넥션에 테넌트를 심는다(트랜잭션 매니저 {@code doBegin} 전용).
   *
   * @param connection 이미 트랜잭션에 바인딩된 커넥션
   * @param tenantId 심을 테넌트 id
   * @throws SQLException 실패 시 그대로 던진다 — 호출자가 트랜잭션 정리 책임을 진다
   */
  void applyToConnection(Connection connection, long tenantId) throws SQLException;

  /**
   * 이미 열린 현재 Spring 트랜잭션에 테넌트를 심는다.
   *
   * @param tenantId 심을 테넌트 id
   */
  void applyToCurrentTransaction(long tenantId);

  /**
   * 이미 열린 현재 Spring 트랜잭션의 테넌트 값을 비운다(WD-1132) — 같은 트랜잭션·같은 커넥션에서 테넌트 범위 작업을 마친 뒤 테넌트 없는(NULL) 행을 써야
   * 할 때 쓴다(운영자 평면 감사: 소유자 변경과 감사를 한 트랜잭션으로 묶는다). 이후 문장은 테넌트 미설정 상태의 RLS 를 받는다.
   *
   * <p>기본 구현은 지원하지 않음으로 던진다 — 필요한 구현체(PG·Tibero)만 재정의한다.
   */
  default void clearCurrentTransaction() {
    throw new UnsupportedOperationException("현재 트랜잭션의 테넌트 값 비우기를 지원하지 않는 구현체다");
  }

  /**
   * 커넥션에 심은 테넌트 값을 지운다(트랜잭션 매니저가 커넥션을 풀에 돌려주기 직전에 부른다, WD-492).
   *
   * <p>기본 구현은 아무것도 하지 않는다 — 값이 트랜잭션-로컬인 DB(PG)는 커밋/롤백이 이미 지웠기 때문이다. 세션 범위 값을 쓰는 구현체만 재정의한다.
   *
   * @param connection 곧 풀로 돌아갈, 방금 끝난 트랜잭션의 커넥션
   * @throws SQLException 실패하면 그대로 던진다 — 호출자가 그 커넥션을 풀에서 버린다
   */
  default void clearConnection(Connection connection) throws SQLException {}
}
