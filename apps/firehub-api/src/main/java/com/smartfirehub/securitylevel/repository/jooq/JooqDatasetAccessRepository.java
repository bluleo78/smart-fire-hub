package com.smartfirehub.securitylevel.repository.jooq;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static com.smartfirehub.jooq.Tables.USER_ROLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.SystemAdminRole;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/**
 * DatasetAccessRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>판정에 필요한 "사실"(데이터셋의 등급 정책 + 이 사용자가 허용 목록에 있는가)과 사용자 자격 원자료를 읽는다. 판정 자체는 하지 않는다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
// 인터셉터·러너·폴러처럼 트랜잭션 없이 부르는 경로가 있다 — 트랜잭션이 있어야 RLS GUC 가 주입된다(없으면 조용히 0행 → 전부 거부).
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class JooqDatasetAccessRepository implements DatasetAccessRepository {

  private final DSLContext dsl;

  @Override
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
  public String visibleSql(DatasetVisibility v, String datasetAlias) {
    return dsl.renderInlined(
        JooqDatasetVisibility.condition(
            v,
            field(name(datasetAlias, "id"), Long.class),
            field(name(datasetAlias, "security_level_id"), Long.class)));
  }

  @Override
  public Map<Long, AccessFacts> findFactsByDatasetIds(Collection<Long> ids, Clearance c) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return fetchFacts(DATASET.ID.in(ids), c);
  }

  @Override
  public Map<String, AccessFacts> findFactsByTableNames(
      Collection<String> tableNames, Clearance c) {
    if (tableNames.isEmpty()) {
      return Map.of();
    }
    Map<Long, AccessFacts> byId = fetchFacts(DATASET.TABLE_NAME.in(tableNames), c);
    Map<String, AccessFacts> byName = new HashMap<>();
    byId.values().forEach(f -> byName.put(f.tableName(), f));
    return byName;
  }

  private Map<Long, AccessFacts> fetchFacts(Condition where, Clearance c) {
    Field<Boolean> onList = field(onAllowlistCondition(c, DATASET.ID)).as("on_list");
    Map<Long, AccessFacts> out = new HashMap<>();
    dsl.select(DATASET.ID, DATASET.TABLE_NAME, onList)
        .select(SECURITY_LEVEL.fields())
        .from(DATASET)
        .join(SECURITY_LEVEL)
        .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
        .where(where)
        .fetch()
        .forEach(
            r ->
                out.put(
                    r.get(DATASET.ID),
                    new AccessFacts(
                        r.get(DATASET.ID),
                        r.get(DATASET.TABLE_NAME),
                        JooqSecurityLevelRepository.toPolicy(r.into(SECURITY_LEVEL)),
                        Boolean.TRUE.equals(r.get(onList)))));
    return out;
  }

  /** "이 사용자 본인 또는 보유 역할이 허용 목록에 있는가" 상관 서브쿼리. 목록 SQL 조각과 사실 조회가 같은 식을 쓰도록 공개한다. */
  public static Condition onAllowlistCondition(Clearance c, Field<Long> datasetIdField) {
    var g = DATASET_ACCESS_GRANT.as("acl_g");
    Condition subject = g.USER_ID.eq(c.userId());
    if (!c.roleIds().isEmpty()) {
      subject = subject.or(g.ROLE_ID.in(c.roleIds()));
    }
    return exists(selectOne().from(g).where(g.DATASET_ID.eq(datasetIdField)).and(subject));
  }

  @Override
  public Set<Long> findRoleIdsWithAllowlistNotViewable(Collection<Long> roleIds, Clearance c) {
    if (roleIds.isEmpty()) {
      return Set.of();
    }
    // onAllowlistCondition 의 별칭(acl_g)과 겹치지 않게 바깥 허용 항목에는 다른 별칭을 쓴다.
    var g = DATASET_ACCESS_GRANT.as("role_g");
    Condition bypass = c.tenantAdmin() ? SECURITY_LEVEL.ADMIN_BYPASS.isTrue() : falseCondition();
    // decide 의 두 거부 절 — CLEARANCE_INSUFFICIENT(rank 초과) 또는 NOT_ON_ALLOWLIST(목록 없음 ∧ 우회 아님).
    Condition notViewable =
        SECURITY_LEVEL.RANK.gt(c.rank()).or(onAllowlistCondition(c, g.DATASET_ID).or(bypass).not());
    return dsl.selectDistinct(g.ROLE_ID)
        .from(g)
        .join(DATASET)
        .on(DATASET.ID.eq(g.DATASET_ID))
        .join(SECURITY_LEVEL)
        .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
        .where(g.ROLE_ID.in(roleIds))
        .and(SECURITY_LEVEL.ALLOWLIST_REQUIRED.isTrue())
        .and(notViewable)
        .fetchSet(g.ROLE_ID);
  }

  @Override
  public ClearanceRow findClearanceRow(long userId, long tenantId) {
    var rows = clearanceRoleRows(USER_ROLE.USER_ID.eq(userId), tenantId);
    Integer max = null;
    Set<Long> roleIds = new HashSet<>();
    boolean admin = false;
    for (var r : rows) {
      roleIds.add(r.get(ROLE.ID));
      int rank = r.get(SECURITY_LEVEL.RANK);
      max = max == null ? rank : Math.max(max, rank);
      admin |= SystemAdminRole.matches(r.get(ROLE.NAME), r.get(ROLE.IS_SYSTEM));
    }
    return new ClearanceRow(max, roleIds, admin);
  }

  @Override
  public Map<Long, Integer> findMaxRanksByUser(Collection<Long> userIds, long tenantId) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Integer> out = new HashMap<>();
    for (var r : clearanceRoleRows(USER_ROLE.USER_ID.in(userIds), tenantId)) {
      out.merge(r.get(USER_ROLE.USER_ID), r.get(SECURITY_LEVEL.RANK), Math::max);
    }
    return out;
  }

  /**
   * 자격 계산 공통 조인 — (사용자, 역할, 역할 등급 rank) 행. ACTIVE 멤버십 + 활성 계정의 이 테넌트 역할만 남긴다. 단건({@link
   * #findClearanceRow})과 일괄({@link #findMaxRanksByUser})이 같은 기준을 쓰도록 한 곳에 둔다(WD-292).
   */
  private org.jooq.Result<org.jooq.Record5<Long, Long, String, Boolean, Integer>> clearanceRoleRows(
      Condition userCondition, long tenantId) {
    return dsl.select(USER_ROLE.USER_ID, ROLE.ID, ROLE.NAME, ROLE.IS_SYSTEM, SECURITY_LEVEL.RANK)
        .from(USER_ROLE)
        .join(ROLE)
        .on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
        .join(SECURITY_LEVEL)
        .on(SECURITY_LEVEL.ID.eq(ROLE.MAX_SECURITY_LEVEL_ID))
        .join(USER)
        .on(USER.ID.eq(USER_ROLE.USER_ID).and(USER.IS_ACTIVE.isTrue()))
        .join(ActiveMembership.TABLE)
        .on(ActiveMembership.of(USER_ROLE.USER_ID, USER_ROLE.TENANT_ID))
        .where(userCondition)
        .and(USER_ROLE.TENANT_ID.eq(tenantId))
        .fetch();
  }
}
