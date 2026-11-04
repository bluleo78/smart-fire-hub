package com.smartfirehub.platform.repository;

import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import java.util.List;
import java.util.Optional;

/**
 * 플랫폼 운영 콘솔용 테넌트(tenant 테이블)와 멤버십(membership) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqPlatformTenantRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PlatformTenantRepository {

  /** 테넌트 행을 만들고 id 를 돌려준다. slug 중복은 UNIQUE 제약이 잡는다. */
  long insertTenant(String slug, String name);

  /**
   * 초기 Owner 멤버십을 만든다. 이미 있으면 아무것도 하지 않는다(멱등).
   *
   * <p>{@code membership_unique(user_id, tenant_id)} 가 있어 ON CONFLICT 로 멱등하게 만든다 — 운영자가 생성을 재시도해도
   * 터지지 않아야 한다.
   */
  void insertOwnerMembership(long tenantId, long userId);

  /** slug 가 이미 쓰이고 있는지. UNIQUE 위반을 500 이 아니라 400 으로 돌려주기 위해 미리 본다. */
  boolean slugExists(String slug);

  /** 테넌트가 있는지. 존재만 확인하는 호출처가 멤버 수 집계까지 하는 {@link #findById} 를 부르지 않게 한다. */
  boolean existsById(long tenantId);

  /** 전체 테넌트 목록(id 오름차순) + 멤버 수 집계. */
  List<TenantSummaryResponse> findAll();

  /** 테넌트 하나. 없으면 empty — 호출처가 404 로 바꾼다. */
  Optional<TenantSummaryResponse> findById(long tenantId);

  /**
   * 테넌트 상태를 바꾸고 바뀐 행 수를 돌려준다. 0 이면 없는 테넌트이거나 이미 그 상태다.
   *
   * <p>{@code status <> ?} 조건이 동시 요청을 가른다(WD-322): 두 트랜잭션이 함께 들어오면 뒤 요청은 앞 요청의 행 잠금을 기다렸다가 커밋된 새
   * 상태로 조건을 다시 평가해 0 행이 된다(PostgreSQL READ COMMITTED). 호출자는 0 이면 감사를 남기지 않는다.
   */
  int updateStatus(long tenantId, String status);

  /**
   * 테넌트의 멤버 목록.
   *
   * <p>{@code "user"} 는 예약어라 인용이 필요하다. 도메인 권한(테넌트 평면 role)은 읽지 않는다 — 그것은 RLS 아래에 있고 운영자에게는 컨텍스트가
   * 없다.
   *
   * <p>전역 계정 활성({@code u.is_active})도 함께 읽는다 — 멤버십이 ACTIVE 여도 운영자가 비활성화한 계정은 로그인할 수 없다(WD-313).
   */
  List<TenantMemberResponse> findMembers(long tenantId);
}
