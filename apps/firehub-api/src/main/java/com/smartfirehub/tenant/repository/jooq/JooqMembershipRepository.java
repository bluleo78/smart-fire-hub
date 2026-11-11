package com.smartfirehub.tenant.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.dto.UnavailableWorkspace;
import com.smartfirehub.tenant.repository.MembershipRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * MembershipRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>사용자-테넌트 멤버십 조회.
 *
 * <p>membership/tenant 는 전역(RLS 미적용) 테이블이다. 테넌트 선택 전에 조회해야 하므로 RLS 를 걸면 순환이 된다 — GUC 가 없어 0행이 나오면
 * 어떤 테넌트를 선택할 수 있는지 알 수 없다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class JooqMembershipRepository implements MembershipRepository {

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

  @Override
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

  @Override
  public List<UnavailableWorkspace> findUnavailableByUser(Long userId) {
    return dsl.select(T_NAME, M_STATUS, T_STATUS)
        .from(MEMBERSHIP)
        .join(TENANT)
        .on(T_ID.eq(M_TENANT_ID))
        .where(M_USER_ID.eq(userId))
        .and(M_STATUS.ne("ACTIVE").or(T_STATUS.ne("ACTIVE")))
        .orderBy(T_ID)
        .fetch(
            r ->
                new UnavailableWorkspace(
                    r.get(T_NAME),
                    "ACTIVE".equals(r.get(T_STATUS))
                        ? UnavailableWorkspace.Reason.MEMBERSHIP_SUSPENDED
                        : UnavailableWorkspace.Reason.WORKSPACE_SUSPENDED));
  }

  @Override
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

  @Override
  public void createDefaultMembership(Long userId) {
    insertMember(userId, DEFAULT_TENANT_ID);
  }

  @Override
  // ── 관리 경로(WD-2): 한 테넌트 안의 멤버십을 상태와 무관하게 다룬다 ──────────────
  // membership 은 RLS 가 없는 전역 테이블이다. 그래서 아래 모든 쿼리는 tenant_id 술어를 직접 건다 —
  // 빠뜨리면 한 테넌트의 관리자가 다른 테넌트 멤버십을 정지·삭제할 수 있다.
  public Optional<TenantMembership> findInTenant(Long userId, long tenantId) {
    return dsl.select(M_USER_ID, M_ROLE, M_STATUS)
        .from(MEMBERSHIP)
        .where(M_USER_ID.eq(userId).and(M_TENANT_ID.eq(tenantId)))
        .fetchOptional(r -> new TenantMembership(r.get(M_USER_ID), r.get(M_ROLE), r.get(M_STATUS)));
  }

  @Override
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

  @Override
  public Set<Long> findActiveMemberIds(long tenantId, Collection<Long> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    Table<?> user = table(name("user"));
    Field<Long> uId = field(name("user", "id"), Long.class);
    Field<Boolean> uActive = field(name("user", "is_active"), Boolean.class);
    return dsl.select(M_USER_ID)
        .from(MEMBERSHIP)
        .join(TENANT)
        .on(T_ID.eq(M_TENANT_ID))
        .join(user)
        .on(uId.eq(M_USER_ID))
        .where(M_TENANT_ID.eq(tenantId))
        .and(M_USER_ID.in(userIds))
        .and(M_STATUS.eq("ACTIVE"))
        .and(T_STATUS.eq("ACTIVE"))
        .and(uActive.isTrue())
        .fetchSet(M_USER_ID);
  }

  @Override
  public void insertMember(Long userId, long tenantId) {
    dsl.insertInto(MEMBERSHIP)
        .set(M_USER_ID, userId)
        .set(M_TENANT_ID, tenantId)
        .set(M_ROLE, "MEMBER")
        .set(M_STATUS, "ACTIVE")
        .execute();
  }

  @Override
  public int updateStatus(Long userId, long tenantId, String status) {
    return dsl.update(MEMBERSHIP)
        .set(M_STATUS, status)
        .where(M_USER_ID.eq(userId).and(M_TENANT_ID.eq(tenantId)))
        .execute();
  }

  @Override
  public int delete(Long userId, long tenantId) {
    return dsl.deleteFrom(MEMBERSHIP)
        .where(M_USER_ID.eq(userId).and(M_TENANT_ID.eq(tenantId)))
        .execute();
  }
}
