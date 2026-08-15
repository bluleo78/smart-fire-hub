package com.smartfirehub.tenant.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.tenant.dto.MembershipResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * 사용자-테넌트 멤버십 조회.
 *
 * <p>membership/tenant 는 전역(RLS 미적용) 테이블이다. 테넌트 선택 전에 조회해야 하므로 RLS 를 걸면
 * 순환이 된다 — GUC 가 없어 0행이 나오면 어떤 테넌트를 선택할 수 있는지 알 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class MembershipRepository {

  /**
   * 회원가입 시 자동 가입되는 기본 테넌트. V81 마이그레이션에서 시드된 tenant.id=1(slug=default).
   *
   * <p>자가 가입 사용자가 합류하는 기본 워크스페이스. 회원가입의 역할 조회도 이 테넌트 컨텍스트에서
   * 일어난다({@code AuthService.signup} 참고) — public 으로 승격해 트랜잭션 밖에서 미리 세팅할 수
   * 있게 한다.
   */
  public static final long DEFAULT_TENANT_ID = 1L;

  private static final Table<?> MEMBERSHIP = table(name("membership"));
  private static final Table<?> TENANT = table(name("tenant"));

  private static final Field<Long> M_USER_ID = field(name("membership", "user_id"), Long.class);
  private static final Field<Long> M_TENANT_ID = field(name("membership", "tenant_id"), Long.class);
  private static final Field<String> M_ROLE = field(name("membership", "role"), String.class);
  private static final Field<String> M_STATUS = field(name("membership", "status"), String.class);

  private static final Field<Long> T_ID = field(name("tenant", "id"), Long.class);
  private static final Field<String> T_SLUG = field(name("tenant", "slug"), String.class);
  private static final Field<String> T_NAME = field(name("tenant", "name"), String.class);
  private static final Field<String> T_STATUS = field(name("tenant", "status"), String.class);

  private final DSLContext dsl;

  /** 사용자가 선택할 수 있는 테넌트 목록. 멤버십과 테넌트가 모두 ACTIVE 인 것만 반환한다. */
  public List<MembershipResponse> findActiveByUser(Long userId) {
    return dsl.select(T_ID, T_SLUG, T_NAME, M_ROLE)
        .from(MEMBERSHIP)
        .join(TENANT)
        .on(T_ID.eq(M_TENANT_ID))
        .where(M_USER_ID.eq(userId))
        .and(M_STATUS.eq("ACTIVE"))
        .and(T_STATUS.eq("ACTIVE"))
        .orderBy(T_ID)
        .fetch(
            r ->
                new MembershipResponse(
                    r.get(T_ID), r.get(T_SLUG), r.get(T_NAME), r.get(M_ROLE)));
  }

  /** 해당 사용자가 그 테넌트의 ACTIVE 멤버이고 테넌트도 ACTIVE 인지. 테넌트 선택/갱신 시의 게이트. */
  public boolean hasActiveMembership(Long userId, Long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(MEMBERSHIP)
            .join(TENANT)
            .on(T_ID.eq(M_TENANT_ID))
            .where(M_USER_ID.eq(userId))
            .and(M_TENANT_ID.eq(tenantId))
            .and(M_STATUS.eq("ACTIVE"))
            .and(T_STATUS.eq("ACTIVE")));
  }

  /**
   * 회원가입한 사용자를 기본 테넌트(id=1)에 ACTIVE MEMBER 로 가입시킨다.
   *
   * <p>왜: 멤버십이 하나도 없는 사용자는 어떤 테넌트도 선택할 수 없어 테넌트 미선택 토큰만 발급받고,
   * RLS 가 모든 행을 막아 모든 API 에서 403 을 받는다(잠김). 자가 가입 사용자를 잠그지 않기 위해
   * 가입과 동시에 기본 워크스페이스에 합류시킨다. 운영자가 다른 테넌트로 프로비저닝하는 것은 이후
   * 단계(operator-driven provisioning)에서 다룬다 — 이 메서드는 그 대상이 아니다.
   */
  public void createDefaultMembership(Long userId) {
    dsl.insertInto(MEMBERSHIP)
        .set(M_USER_ID, userId)
        .set(M_TENANT_ID, DEFAULT_TENANT_ID)
        .set(M_ROLE, "MEMBER")
        .set(M_STATUS, "ACTIVE")
        .execute();
  }
}
