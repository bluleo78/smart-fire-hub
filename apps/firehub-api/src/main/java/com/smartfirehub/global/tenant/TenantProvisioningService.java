package com.smartfirehub.global.tenant;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 테넌트에 기본 RBAC·내장 양식을 시드한다.
 *
 * <p>정의의 정본은 V98 의 SQL 함수 {@code provision_tenant_defaults} 다 — 테넌트 생성이 아직
 * 운영자 SQL 이라, 앱에만 로직을 두면 운영자가 만든 테넌트는 영원히 비어 있게 된다. 이 클래스는
 * 같은 함수를 앱에서도 부를 수 있게 하는 얇은 래퍼다.
 *
 * <p>이 시드가 없으면 신규 테넌트는 역할·권한 0개라 멤버십이 있어도 모든 API 가 403 이다.
 */
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

  private final DSLContext dsl;

  /**
   * 대상 테넌트에 시스템 역할·권한매핑·내장 양식을 채운다. 멱등하므로 재실행해도 안전하다.
   *
   * <p>SECURITY DEFINER 함수라 호출자의 테넌트 컨텍스트와 무관하게 동작한다.
   */
  @Transactional
  public void provisionDefaults(long tenantId) {
    dsl.execute("select provision_tenant_defaults(?)", tenantId);
  }
}
