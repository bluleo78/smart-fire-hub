package com.smartfirehub.platform.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PlatformRoleRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>플랫폼(운영자) 평면 RBAC 조회와, 첫 가입자에게 플랫폼 역할을 주는 쓰기 하나(WD-748). V82 의 {@code platform_role} / {@code
 * platform_role_permission} / {@code platform_user_role} 을 읽는다.
 *
 * <p>왜 테넌트 평면의 {@code PermissionRepository} 와 합치지 않는가: 그 리포지토리는 {@code role_permission ⨝ user_role}
 * 만 조인하고 두 테이블은 RLS 아래에 있다(V99). 플랫폼 테이블은 테넌트 경계 위에 있어 {@code tenant_id} 도 RLS 도 없다. 그리고 두 평면의 권한
 * 집합을 하나로 합치면 평면 구분이 사라져, 운영자 토큰으로 테넌트 API 를 부르거나 그 반대가 가능해진다.
 *
 * <p>클래스 레벨 {@code @Transactional(readOnly)} 을 붙이는 이유: 이 조회는 인증 필터에서 불리고 그 시점에는 주변 트랜잭션이 없다. 플랫폼
 * 테이블은 RLS 가 없으므로 GUC 주입은 불필요하지만, 커넥션 획득과 조회를 한 단위로 묶어 두는 편이 명시적이다. 쓰기 메서드는 메서드 레벨
 * {@code @Transactional} 로 readOnly 를 덮는다 — PG 는 읽기 전용 트랜잭션의 INSERT 를 거부한다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@Transactional(readOnly = true)
public class JooqPlatformRoleRepository implements PlatformRoleRepository {

  private final DSLContext dsl;

  public JooqPlatformRoleRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
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

  @Override
  public boolean hasAnyPlatformRole(Long userId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("platform_user_role")))
            .where(field(name("platform_user_role", "user_id"), Long.class).eq(userId)));
  }

  /**
   * INSERT ... SELECT 로 이름에 맞는 역할 id 를 찾아 넣는다 — 그 이름의 역할이 없으면 0행이라 false.
   *
   * <p>클래스 레벨 readOnly 를 덮어 쓰기 트랜잭션으로 돈다. 주변 트랜잭션(가입)이 있으면 거기에 참여해 함께 커밋·롤백된다.
   */
  @Override
  @Transactional
  public boolean grantPlatformRole(Long userId, String roleName) {
    return dsl.insertInto(
                table(name("platform_user_role")),
                field(name("user_id"), Long.class),
                field(name("platform_role_id"), Long.class))
            .select(
                dsl.select(DSL.val(userId), field(name("id"), Long.class))
                    .from(table(name("platform_role")))
                    .where(field(name("name"), String.class).eq(roleName)))
            .execute()
        > 0;
  }
}
