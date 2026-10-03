package com.smartfirehub.tenant.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.TenantMembership;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * 사용자-테넌트 멤버십 조회.
 *
 * <p>membership/tenant 는 전역(RLS 미적용) 테이블이다. 테넌트 선택 전에 조회해야 하므로 RLS 를 걸면 순환이 된다 — GUC 가 없어 0행이 나오면
 * 어떤 테넌트를 선택할 수 있는지 알 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class MembershipRepository {

  /**
   * 회원가입 시 자동 가입되는 기본 테넌트. V81 마이그레이션에서 시드된 tenant.id=1(slug=default).
   *
   * <p>자가 가입 사용자가 합류하는 기본 워크스페이스. 회원가입의 역할 조회도 이 테넌트 컨텍스트에서 일어난다({@code AuthService.signup} 참고) —
   * public 으로 승격해 트랜잭션 밖에서 미리 세팅할 수 있게 한다.
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
            r -> new MembershipResponse(r.get(T_ID), r.get(T_SLUG), r.get(T_NAME), r.get(M_ROLE)));
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
   * <p>왜: 멤버십이 하나도 없는 사용자는 어떤 테넌트도 선택할 수 없어 테넌트 미선택 토큰만 발급받고, RLS 가 모든 행을 막아 모든 API 에서 403 을
   * 받는다(잠김). 자가 가입 사용자를 잠그지 않기 위해 가입과 동시에 기본 워크스페이스에 합류시킨다. 운영자가 다른 테넌트로 프로비저닝하는 것은 이후
   * 단계(operator-driven provisioning)에서 다룬다 — 이 메서드는 그 대상이 아니다.
   */
  public void createDefaultMembership(Long userId) {
    insertMember(userId, DEFAULT_TENANT_ID);
  }

  // ── 관리 경로(WD-2): 한 테넌트 안의 멤버십을 상태와 무관하게 다룬다 ──────────────
  // membership 은 RLS 가 없는 전역 테이블이다. 그래서 아래 모든 쿼리는 tenant_id 술어를 직접 건다 —
  // 빠뜨리면 한 테넌트의 관리자가 다른 테넌트 멤버십을 정지·삭제할 수 있다.

  /** 이 테넌트에서의 멤버십(ACTIVE·SUSPENDED 모두). tenant.status 는 보지 않는다(관리 경로). */
  public Optional<TenantMembership> findInTenant(Long userId, long tenantId) {
    return dsl.select(M_USER_ID, M_ROLE, M_STATUS)
        .from(MEMBERSHIP)
        .where(M_USER_ID.eq(userId).and(M_TENANT_ID.eq(tenantId)))
        .fetchOptional(r -> new TenantMembership(r.get(M_USER_ID), r.get(M_ROLE), r.get(M_STATUS)));
  }

  /** 목록 화면용 배치 조회 — 사용자마다 조회하면 페이지 크기만큼 N+1 이 된다. */
  public Map<Long, TenantMembership> findInTenant(long tenantId, Collection<Long> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    return dsl.select(M_USER_ID, M_ROLE, M_STATUS)
        .from(MEMBERSHIP)
        .where(M_TENANT_ID.eq(tenantId).and(M_USER_ID.in(userIds)))
        .fetchMap(
            r -> r.get(M_USER_ID),
            r -> new TenantMembership(r.get(M_USER_ID), r.get(M_ROLE), r.get(M_STATUS)));
  }

  /** 멤버 추가: 이 테넌트의 ACTIVE MEMBER 로 넣는다. 중복은 호출자가 먼저 걸러 409 로 응답한다. */
  public void insertMember(Long userId, long tenantId) {
    dsl.insertInto(MEMBERSHIP)
        .set(M_USER_ID, userId)
        .set(M_TENANT_ID, tenantId)
        .set(M_ROLE, "MEMBER")
        .set(M_STATUS, "ACTIVE")
        .execute();
  }

  /** 정지/재활성. 이 테넌트 행만 바꾼다. 반환값은 바뀐 행 수(0 이면 비멤버). */
  public int updateStatus(Long userId, long tenantId, String status) {
    return dsl.update(MEMBERSHIP)
        .set(M_STATUS, status)
        .where(M_USER_ID.eq(userId).and(M_TENANT_ID.eq(tenantId)))
        .execute();
  }

  /** 테넌트에서 제거. 계정·다른 테넌트 멤버십은 그대로다. */
  public int delete(Long userId, long tenantId) {
    return dsl.deleteFrom(MEMBERSHIP)
        .where(M_USER_ID.eq(userId).and(M_TENANT_ID.eq(tenantId)))
        .execute();
  }
}
