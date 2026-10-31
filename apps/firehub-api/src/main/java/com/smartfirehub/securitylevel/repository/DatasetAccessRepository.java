package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static com.smartfirehub.jooq.Tables.USER_ROLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
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

/** 판정에 필요한 "사실"(데이터셋의 등급 정책 + 이 사용자가 허용 목록에 있는가)과 사용자 자격 원자료를 읽는다. 판정 자체는 하지 않는다. */
@Repository
@RequiredArgsConstructor
// 인터셉터·러너·폴러처럼 트랜잭션 없이 부르는 경로가 있다 — 트랜잭션이 있어야 RLS GUC 가 주입된다(없으면 조용히 0행 → 전부 거부).
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class DatasetAccessRepository {

  private final DSLContext dsl;

  /** 데이터셋 1개에 대한 판정 사실. */
  public record AccessFacts(
      long datasetId, String tableName, LevelPolicy level, boolean onAllowlist) {}

  /** 자격 계산 원자료. maxRank 가 null 이면 역할이 없다. */
  public record ClearanceRow(Integer maxRank, Set<Long> roleIds, boolean tenantAdmin) {}

  public Map<Long, AccessFacts> findFactsByDatasetIds(Collection<Long> ids, Clearance c) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return fetchFacts(DATASET.ID.in(ids), c);
  }

  /** SQL 경로용 — data 스키마 테이블 이름(소문자 폴딩 결과)으로 데이터셋을 찾는다. */
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
                        SecurityLevelRepository.toPolicy(r.into(SECURITY_LEVEL)),
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

  /**
   * 주어진 역할들의 허용 항목 중, 허용 목록 필요 등급 데이터셋이면서 호출자 {@code c} 가 열람 시점에 <b>볼 수 없는</b> 데이터셋을 가진 역할
   * id(WD-140 역할 부여 범위 검사용).
   *
   * <p>"볼 수 있는가" 는 열람 판정({@link com.smartfirehub.securitylevel.access.DatasetAccessPolicy#decide})의
   * VIEW 와 같은 식이다 — 등급 rank ≤ 호출자 rank 이고, (허용 목록에 있음({@link #onAllowlistCondition}) 또는
   * admin_bypass 등급 ∧ 시스템 ADMIN). 이것의 부정(rank 초과 또는 허용 목록 미충족)이면 그 역할을 돌려준다. 허용 목록 필요가 아닌 등급의 허용
   * 항목은 열람 때 아예 참조되지 않으므로 제외한다 — 그런 데이터셋의 열람은 허용 항목이 아니라 등급만으로 정해지고, 등급은 가드가 역할 등급 상한으로 따로 본다.
   *
   * <p>한 번의 조회로 끝난다(데이터셋마다 묻지 않는다). dataset.security_level_id 는 NOT NULL + FK 라 내부 조인이 행을 흘리지 않는다.
   */
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

  /**
   * 자격 원자료. ACTIVE 멤버십 + 활성 계정의 역할만 센다(권한 조회 {@code findPermissionCodesByUserId} 와 같은 조인 — 정지 멤버는
   * 자격 0). membership 은 전역 테이블이라 tenant_id 를 명시한다.
   */
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

  /**
   * 여러 사용자의 유효 열람 rank(보유 역할 등급의 최댓값)를 한 번에 읽는다(WD-292 — 허용 목록 후보·항목에 대상 등급을 싣기 위해).
   *
   * <p>왜 따로 계산하지 않고 이 메서드인가: {@link #findClearanceRow} 와 같은 조인({@link #clearanceRoleRows})을 써야 화면에
   * 보이는 등급이 실제 판정({@code ClearanceResolver.resolve})과 어긋나지 않는다. 사용자마다 resolve 를 부르면 권한 조회까지 N 번 돈다.
   *
   * @return userId → 최대 rank. 역할이 없는(정지·비활성 포함) 사용자는 키가 없다 — 어떤 등급도 못 보는 자격(NO_RANK)
   */
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
