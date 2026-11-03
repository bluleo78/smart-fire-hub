package com.smartfirehub.global.platformdb;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 플랫폼 DB 세션에 활성 테넌트를 심는 포트(WD-486).
 *
 * <p>RLS 가 테넌트를 가르는 데 쓰는 세션 값을 DB 종류별 문법으로 설정하는 일을 인터페이스 뒤로 숨긴다. 호출자(트랜잭션 매니저, 로그인 경로)는 "테넌트를 심는다"만
 * 알고 DB 별 SQL 은 구현체가 안다. 심은 값은 <b>트랜잭션-로컬</b>이어야 한다 — 커밋/롤백과 함께 사라져야 풀에 반납된 커넥션이 다음 요청에 이전 테넌트를
 * 물려주지 않는다.
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
}
