package com.smartfirehub.tenant.repository;

import java.util.List;

/**
 * 전역 tenant 테이블 조회와 테넌트 기본값 시드 함수 호출 리포지토리.
 *
 * <p>구현은 {@code JooqTenantRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface TenantRepository {

  /** ACTIVE 테넌트 id 를 오름차순으로 반환한다. 배경 작업의 테넌트 순회에 쓴다. */
  List<Long> findActiveTenantIds();

  /**
   * 이 테넌트가 지금 ACTIVE 인지(WD-174). 없는 테넌트도 false 다.
   *
   * <p>예약 트리거·스마트 작업처럼 사람이 없을 때 도는 일의 발화 시점 관문에 쓴다 — 운영자가 워크스페이스를 정지해도 이미 등록된 cron 이 남아 매 주기 실행·AI
   * 호출·메일 발송이 계속되던 결함의 수정. 기동 순회({@link #findActiveTenantIds})는 정지 테넌트를 건너뛰지만, 실행 중 정지는 등록된 예약을 그대로
   * 둔다. 발화마다 한 번 읽는 비용(매분 수준)은 허용한다.
   */
  boolean isActive(long tenantId);

  /**
   * 대상 테넌트에 기본 역할·권한매핑·내장 양식을 시드하는 SQL 함수 {@code provision_tenant_defaults}(V98)를 호출한다.
   *
   * <p>호출 SQL 은 서비스에 있던 문장 그대로다(WD-486 — 서비스에서 DSLContext 를 없애려 리포지토리로 옮겼을 뿐 동작은 같다). 호출자인
   * TenantProvisioningService 가 이미 트랜잭션을 열어 두므로 여기서는 합류해야 한다(MANDATORY) — 트랜잭션 밖에서 부르면 스스로 열지 않고
   * 예외로 거부한다(시드가 부분 커밋되는 것을 막는다).
   */
  void provisionDefaults(long tenantId);
}
