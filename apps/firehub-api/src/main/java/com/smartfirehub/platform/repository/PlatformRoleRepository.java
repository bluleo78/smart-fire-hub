package com.smartfirehub.platform.repository;

import java.util.Set;

/**
 * 플랫폼 운영자 역할(platform_user_role·platform_role_permission·permission 테이블) 리포지토리.
 *
 * <p>구현은 {@code JooqPlatformRoleRepository}(jOOQ/PostgreSQL, WD-485) 와 {@code
 * MyBatisPlatformRoleRepository}(MyBatis — PostgreSQL·Tibero 공통, WD-706) 두 벌이다. 조회 둘에 더해 쓰기는 첫
 * 가입자에게 플랫폼 역할을 주는 {@link #grantPlatformRole} 하나뿐이다(WD-748).
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

  /**
   * 사용자에게 플랫폼 역할을 <b>이름으로</b> 찾아 준다(WD-748 — 새 설치본의 첫 가입자에게 SUPER_ADMIN).
   *
   * <p>역할 id 를 숫자로 박지 않는 이유: PG(V82 시퀀스)와 Tibero(V155.1 시드)의 id 가 같다는 보장에 기대지 않기 위해서다.
   *
   * <p>그 이름의 역할이 없으면 예외가 아니라 {@code false} 를 돌려준다 — 시드 누락을 어떻게 다룰지는 호출자가 정한다. 이미 가진 역할을 다시 주면 PK
   * 위반이 난다(멱등 처리 없음 — 벤더별 ON CONFLICT/MERGE 문법이 갈리고, 호출처는 사용자 0명일 때의 첫 가입 하나뿐이라 일어나지 않는다).
   *
   * @return 행을 넣었으면 {@code true}, 그 이름의 역할이 없으면 {@code false}
   */
  boolean grantPlatformRole(Long userId, String roleName);
}
