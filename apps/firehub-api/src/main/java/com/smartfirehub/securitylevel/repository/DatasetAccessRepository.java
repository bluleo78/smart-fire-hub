package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static com.smartfirehub.jooq.Tables.USER_ROLE;
import static org.jooq.impl.DSL.exists;
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
   * 자격 원자료. ACTIVE 멤버십 + 활성 계정의 역할만 센다(권한 조회 {@code findPermissionCodesByUserId} 와 같은 조인 — 정지 멤버는
   * 자격 0). membership 은 전역 테이블이라 tenant_id 를 명시한다.
   */
  public ClearanceRow findClearanceRow(long userId, long tenantId) {
    var rows =
        dsl.select(ROLE.ID, ROLE.NAME, ROLE.IS_SYSTEM, SECURITY_LEVEL.RANK)
            .from(USER_ROLE)
            .join(ROLE)
            .on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
            .join(SECURITY_LEVEL)
            .on(SECURITY_LEVEL.ID.eq(ROLE.MAX_SECURITY_LEVEL_ID))
            .join(USER)
            .on(USER.ID.eq(USER_ROLE.USER_ID).and(USER.IS_ACTIVE.isTrue()))
            .join(ActiveMembership.TABLE)
            .on(ActiveMembership.of(USER_ROLE.USER_ID, USER_ROLE.TENANT_ID))
            .where(USER_ROLE.USER_ID.eq(userId))
            .and(USER_ROLE.TENANT_ID.eq(tenantId))
            .fetch();
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
}
