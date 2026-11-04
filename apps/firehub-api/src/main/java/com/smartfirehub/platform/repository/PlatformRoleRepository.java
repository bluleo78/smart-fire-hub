package com.smartfirehub.platform.repository;

import java.util.Set;

/**
 * 플랫폼 운영자 역할(platform_user_role·platform_role_permission·permission 테이블) 조회 리포지토리.
 *
 * <p>구현은 {@code JooqPlatformRoleRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PlatformRoleRepository {

  /**
   * 사용자의 플랫폼 권한 코드.
   *
   * <p>플랫폼 롤이 없으면 예외가 아니라 <b>빈 집합</b>을 돌려준다 — 호출처(인증 필터)가 그것을 권한 0개로 반영하고, 결과적으로 모든
   * {@code @RequirePermission} 엔드포인트가 403 이 된다. 그것이 의도된 fail-closed 결과다.
   */
  Set<String> findPlatformPermissionCodes(Long userId);

  /** 플랫폼 롤을 하나라도 갖고 있는지. 운영자 로그인 자격 판정에 쓴다. */
  boolean hasAnyPlatformRole(Long userId);
}
