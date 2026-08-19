package com.smartfirehub.platform.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼(운영자) 평면 RBAC 조회. V82 의 {@code platform_role} / {@code platform_role_permission} /
 * {@code platform_user_role} 을 읽는다.
 *
 * <p>왜 테넌트 평면의 {@code PermissionRepository} 와 합치지 않는가: 그 리포지토리는
 * {@code role_permission ⨝ user_role} 만 조인하고 두 테이블은 RLS 아래에 있다(V99). 플랫폼 테이블은
 * 테넌트 경계 위에 있어 {@code tenant_id} 도 RLS 도 없다. 그리고 두 평면의 권한 집합을 하나로 합치면
 * 평면 구분이 사라져, 운영자 토큰으로 테넌트 API 를 부르거나 그 반대가 가능해진다.
 *
 * <p>클래스 레벨 {@code @Transactional(readOnly)} 을 붙이는 이유: 이 조회는 인증 필터에서 불리고 그
 * 시점에는 주변 트랜잭션이 없다. 플랫폼 테이블은 RLS 가 없으므로 GUC 주입은 불필요하지만, 커넥션
 * 획득과 조회를 한 단위로 묶어 두는 편이 명시적이다.
 */
@Repository
@Transactional(readOnly = true)
public class PlatformRoleRepository {

  private final DSLContext dsl;

  public PlatformRoleRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * 사용자의 플랫폼 권한 코드.
   *
   * <p>플랫폼 롤이 없으면 예외가 아니라 <b>빈 집합</b>을 돌려준다 — 호출처(인증 필터)가 그것을
   * 권한 0개로 반영하고, 결과적으로 모든 {@code @RequirePermission} 엔드포인트가 403 이 된다.
   * 그것이 의도된 fail-closed 결과다.
   */
  public Set<String> findPlatformPermissionCodes(Long userId) {
    return dsl.selectDistinct(field(name("p", "code"), String.class))
        .from(table(name("platform_user_role")).as("pur"))
        .join(table(name("platform_role_permission")).as("prp"))
        .on(field(name("prp", "platform_role_id")).eq(field(name("pur", "platform_role_id"))))
        .join(table(name("permission")).as("p"))
        .on(field(name("p", "id")).eq(field(name("prp", "permission_id"))))
        .where(field(name("pur", "user_id"), Long.class).eq(userId))
        .fetchSet(field(name("p", "code"), String.class));
  }

  /** 플랫폼 롤을 하나라도 갖고 있는지. 운영자 로그인 자격 판정에 쓴다. */
  public boolean hasAnyPlatformRole(Long userId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("platform_user_role")))
            .where(field(name("platform_user_role", "user_id"), Long.class).eq(userId)));
  }
}
