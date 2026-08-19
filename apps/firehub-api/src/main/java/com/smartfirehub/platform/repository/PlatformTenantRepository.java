package com.smartfirehub.platform.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * 운영자 평면의 {@code tenant} / {@code membership} 접근.
 *
 * <p>두 테이블은 테넌트 경계 <b>위</b>에 있어 {@code tenant_id} 컬럼도 RLS 도 없다(V81) — 그래서
 * 운영자 토큰처럼 테넌트 컨텍스트가 비어 있어도 조회·수정이 된다. 이 리포지토리는 그 두 테이블만
 * 만지며, RLS 가 걸린 도메인 테이블은 <b>일절 건드리지 않는다</b>(설계서 §4: 크로스테넌트 도메인
 * 조회를 제공하지 않는다).
 *
 * <p>트랜잭션 경계는 서비스가 잡는다 — 테넌트 생성은 여러 쓰기가 원자적이어야 한다.
 */
@Repository
public class PlatformTenantRepository {

  private final DSLContext dsl;

  public PlatformTenantRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 테넌트 행을 만들고 id 를 돌려준다. slug 중복은 UNIQUE 제약이 잡는다. */
  public long insertTenant(String slug, String name) {
    return dsl.insertInto(table(name("tenant")))
        .set(field(name("slug")), slug)
        .set(field(name("name")), name)
        .set(field(name("status")), "ACTIVE")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /**
   * 초기 Owner 멤버십을 만든다. 이미 있으면 아무것도 하지 않는다(멱등).
   *
   * <p>{@code membership_unique(user_id, tenant_id)} 가 있어 ON CONFLICT 로 멱등하게 만든다 —
   * 운영자가 생성을 재시도해도 터지지 않아야 한다.
   */
  public void insertOwnerMembership(long tenantId, long userId) {
    dsl.execute(
        "insert into membership (user_id, tenant_id, role, status)"
            + " values (?, ?, 'OWNER', 'ACTIVE')"
            + " on conflict (user_id, tenant_id) do nothing",
        userId,
        tenantId);
  }


  /** slug 가 이미 쓰이고 있는지. UNIQUE 위반을 500 이 아니라 400 으로 돌려주기 위해 미리 본다. */
  public boolean slugExists(String slug) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("tenant")))
            .where(field(name("slug"), String.class).eq(slug)));
  }

  /** 전체 테넌트 목록(id 오름차순) + 멤버 수 집계. */
  public List<TenantSummaryResponse> findAll() {
    return dsl.fetch(
            """
            select t.id, t.slug, t.name, t.status, t.created_at,
                   (select count(*) from membership m where m.tenant_id = t.id) as member_count
            from tenant t
            order by t.id
            """)
        .map(PlatformTenantRepository::toSummary);
  }

  /** 테넌트 하나. 없으면 empty — 호출처가 404 로 바꾼다. */
  public Optional<TenantSummaryResponse> findById(long tenantId) {
    return dsl.fetch(
            """
            select t.id, t.slug, t.name, t.status, t.created_at,
                   (select count(*) from membership m where m.tenant_id = t.id) as member_count
            from tenant t
            where t.id = ?
            """,
            tenantId)
        .map(PlatformTenantRepository::toSummary)
        .stream()
        .findFirst();
  }

  /** 테넌트 상태를 바꾸고 바뀐 행 수를 돌려준다. 0 이면 없는 테넌트다. */
  public int updateStatus(long tenantId, String status) {
    return dsl.execute("update tenant set status = ? where id = ?", status, tenantId);
  }

  /**
   * 테넌트의 멤버 목록.
   *
   * <p>{@code "user"} 는 예약어라 인용이 필요하다. 도메인 권한(테넌트 평면 role)은 읽지 않는다 —
   * 그것은 RLS 아래에 있고 운영자에게는 컨텍스트가 없다.
   */
  public List<TenantMemberResponse> findMembers(long tenantId) {
    return dsl.fetch(
            """
            select u.id as user_id, u.username, u.email, m.role, m.status
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
                    r.get("status", String.class)));
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
