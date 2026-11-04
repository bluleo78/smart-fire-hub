package com.smartfirehub.platform.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * PlatformTenantRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>운영자 평면의 {@code tenant} / {@code membership} 접근.
 *
 * <p>두 테이블은 테넌트 경계 <b>위</b>에 있어 {@code tenant_id} 컬럼도 RLS 도 없다(V81) — 그래서 운영자 토큰처럼 테넌트 컨텍스트가 비어 있어도
 * 조회·수정이 된다. 이 리포지토리는 그 두 테이블만 만지며, RLS 가 걸린 도메인 테이블은 <b>일절 건드리지 않는다</b>(설계서 §4: 크로스테넌트 도메인 조회를
 * 제공하지 않는다).
 *
 * <p>트랜잭션 경계는 서비스가 잡는다 — 테넌트 생성은 여러 쓰기가 원자적이어야 한다.
 */
@Repository
public class JooqPlatformTenantRepository implements PlatformTenantRepository {

  private final DSLContext dsl;

  public JooqPlatformTenantRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public long insertTenant(String slug, String name) {
    return dsl.insertInto(table(name("tenant")))
        .set(field(name("slug")), slug)
        .set(field(name("name")), name)
        .set(field(name("status")), "ACTIVE")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  @Override
  public void insertOwnerMembership(long tenantId, long userId) {
    dsl.execute(
        "insert into membership (user_id, tenant_id, role, status)"
            + " values (?, ?, 'OWNER', 'ACTIVE')"
            + " on conflict (user_id, tenant_id) do nothing",
        userId,
        tenantId);
  }

  @Override
  public boolean slugExists(String slug) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("tenant")))
            .where(field(name("slug"), String.class).eq(slug)));
  }

  @Override
  public boolean existsById(long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("tenant")))
            .where(field(name("id"), Long.class).eq(tenantId)));
  }

  @Override
  public List<TenantSummaryResponse> findAll() {
    return dsl.fetch(
            """
            select t.id, t.slug, t.name, t.status, t.created_at,
                   (select count(*) from membership m where m.tenant_id = t.id) as member_count
            from tenant t
            order by t.id
            """)
        .map(JooqPlatformTenantRepository::toSummary);
  }

  @Override
  public Optional<TenantSummaryResponse> findById(long tenantId) {
    return dsl
        .fetch(
            """
            select t.id, t.slug, t.name, t.status, t.created_at,
                   (select count(*) from membership m where m.tenant_id = t.id) as member_count
            from tenant t
            where t.id = ?
            """,
            tenantId)
        .map(JooqPlatformTenantRepository::toSummary)
        .stream()
        .findFirst();
  }

  @Override
  public int updateStatus(long tenantId, String status) {
    return dsl.execute(
        "update tenant set status = ? where id = ? and status <> ?", status, tenantId, status);
  }

  @Override
  public List<TenantMemberResponse> findMembers(long tenantId) {
    return dsl.fetch(
            """
            select u.id as user_id, u.username, u.email, m.role, m.status, u.is_active
            from membership m
            join "user" u on u.id = m.user_id
            where m.tenant_id = ?
            order by u.id
            """,
            tenantId)
        .map(
            r ->
                new TenantMemberResponse(
                    r.get("user_id", Long.class),
                    r.get("username", String.class),
                    r.get("email", String.class),
                    r.get("role", String.class),
                    r.get("status", String.class),
                    Boolean.TRUE.equals(r.get("is_active", Boolean.class))));
  }

  private static TenantSummaryResponse toSummary(Record r) {
    return new TenantSummaryResponse(
        r.get("id", Long.class),
        r.get("slug", String.class),
        r.get("name", String.class),
        r.get("status", String.class),
        r.get("member_count", Integer.class),
        r.get("created_at", java.time.LocalDateTime.class));
  }
}
