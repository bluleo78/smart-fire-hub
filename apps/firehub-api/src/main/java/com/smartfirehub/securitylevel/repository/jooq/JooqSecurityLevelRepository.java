package com.smartfirehub.securitylevel.repository.jooq;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static com.smartfirehub.jooq.Tables.USER_ROLE;
import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.jooq.tables.records.SecurityLevelRecord;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.SystemAdminRole;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * SecurityLevelRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>security_level 접근. 테넌트 범위는 RLS(GUC)가 정한다 — GUC 는 트랜잭션 시작 때만 주입되므로 클래스 레벨 @Transactional 로
 * "트랜잭션 없는 호출자"(비동기 러너·인터셉터)도 안전하게 한다(DatasetRepository 와 같은 관례). 호출자 트랜잭션이 있으면 합류한다.
 */
@Repository
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class JooqSecurityLevelRepository implements SecurityLevelRepository {

  /**
   * SQL 조건 — ROLE 테이블 행이 시스템 ADMIN 인가. 판정 규칙은 {@link SystemAdminRole#matches} 와 같다(이름은 {@link
   * SystemAdminRole#NAME} 하나를 공유). jOOQ 타입이라 인터페이스 패키지의 {@code SystemAdminRole} 에서 이 구현으로
   * 옮겼다(WD-485).
   */
  private static final Condition SYSTEM_ADMIN_ROLE =
      ROLE.NAME.eq(SystemAdminRole.NAME).and(ROLE.IS_SYSTEM.isTrue());

  private final DSLContext dsl;

  /** 등급 정의 변경 직렬화 잠금 — PG advisory 호출을 플랫폼 잠금 추상화로 위임한다(WD-486). */
  private final PlatformLocks locks;

  @Override
  public List<LevelPolicy> findAll() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .orderBy(SECURITY_LEVEL.RANK.asc())
        .fetch(JooqSecurityLevelRepository::toPolicy);
  }

  @Override
  public Optional<LevelPolicy> findById(long id) {
    return dsl.selectFrom(SECURITY_LEVEL)
        .where(SECURITY_LEVEL.ID.eq(id))
        .fetchOptional(JooqSecurityLevelRepository::toPolicy);
  }

  @Override
  public Map<Long, Integer> rankById() {
    return findAll().stream().collect(Collectors.toMap(LevelPolicy::id, LevelPolicy::rank));
  }

  @Override
  public Long findDatasetLevelId(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .fetchSingle(DATASET.SECURITY_LEVEL_ID);
  }

  @Override
  public Set<Long> findAuditedDatasetIds(Collection<Long> datasetIds) {
    return findDatasetIdsWhereLevel(datasetIds, SECURITY_LEVEL.AUDIT_ACCESS.isTrue());
  }

  @Override
  public Set<Long> findAllowlistRequiredDatasetIds(Collection<Long> datasetIds) {
    return findDatasetIdsWhereLevel(datasetIds, SECURITY_LEVEL.ALLOWLIST_REQUIRED.isTrue());
  }

  /** 주어진 데이터셋 중 등급이 조건을 만족하는 것 — 등급 플래그별 필터 조회의 공통 본문. 빈 입력은 조회 없이 빈 집합. */
  private Set<Long> findDatasetIdsWhereLevel(
      Collection<Long> datasetIds, Condition levelCondition) {
    if (datasetIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        dsl.select(DATASET.ID)
            .from(DATASET)
            .join(SECURITY_LEVEL)
            .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
            .where(DATASET.ID.in(datasetIds))
            .and(levelCondition)
            .fetch(DATASET.ID));
  }

  @Override
  public Optional<LevelPolicy> findDefault() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
        .fetchOptional(JooqSecurityLevelRepository::toPolicy);
  }

  @Override
  public void lockTenantLevelDefinitions(long tenantId) {
    locks.xactLock("security_level_defs_t" + tenantId);
  }

  @Override
  public LevelPolicy findTop() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .orderBy(SECURITY_LEVEL.RANK.desc())
        .limit(1)
        .fetchSingle(JooqSecurityLevelRepository::toPolicy);
  }

  @Override
  public boolean existsByName(String name, Long excludeId) {
    var cond = SECURITY_LEVEL.NAME.eq(name.trim());
    if (excludeId != null) {
      cond = cond.and(SECURITY_LEVEL.ID.ne(excludeId));
    }
    return dsl.fetchExists(SECURITY_LEVEL, cond);
  }

  @Override
  public long insert(SecurityLevelRequest req, int rank, long actor) {
    return dsl.insertInto(SECURITY_LEVEL)
        .set(SECURITY_LEVEL.RANK, rank)
        .set(SECURITY_LEVEL.NAME, req.name().trim())
        .set(SECURITY_LEVEL.ALLOWLIST_REQUIRED, req.allowlistRequired())
        .set(SECURITY_LEVEL.ADMIN_BYPASS, req.adminBypass())
        .set(SECURITY_LEVEL.EXPORT_POLICY, req.exportPolicy().name())
        .set(SECURITY_LEVEL.AI_POLICY, req.aiPolicy().name())
        .set(SECURITY_LEVEL.SHARE_POLICY, req.sharePolicy().name())
        .set(SECURITY_LEVEL.AUDIT_ACCESS, req.auditAccess())
        .set(SECURITY_LEVEL.CREATED_BY, actor)
        .set(SECURITY_LEVEL.UPDATED_BY, actor)
        .returning(SECURITY_LEVEL.ID)
        .fetchSingle(SECURITY_LEVEL.ID);
  }

  @Override
  public void update(long id, SecurityLevelRequest req, long actor) {
    dsl.update(SECURITY_LEVEL)
        .set(SECURITY_LEVEL.NAME, req.name().trim())
        .set(SECURITY_LEVEL.ALLOWLIST_REQUIRED, req.allowlistRequired())
        .set(SECURITY_LEVEL.ADMIN_BYPASS, req.adminBypass())
        .set(SECURITY_LEVEL.EXPORT_POLICY, req.exportPolicy().name())
        .set(SECURITY_LEVEL.AI_POLICY, req.aiPolicy().name())
        .set(SECURITY_LEVEL.SHARE_POLICY, req.sharePolicy().name())
        .set(SECURITY_LEVEL.AUDIT_ACCESS, req.auditAccess())
        .set(SECURITY_LEVEL.UPDATED_BY, actor)
        .set(SECURITY_LEVEL.UPDATED_AT, java.time.LocalDateTime.now())
        .where(SECURITY_LEVEL.ID.eq(id))
        .execute();
  }

  @Override
  public int setDefault(long id) {
    dsl.update(SECURITY_LEVEL)
        .set(SECURITY_LEVEL.IS_DEFAULT, false)
        .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
        .execute();
    return dsl.update(SECURITY_LEVEL)
        .set(SECURITY_LEVEL.IS_DEFAULT, true)
        .where(SECURITY_LEVEL.ID.eq(id))
        .execute();
  }

  @Override
  public void delete(long id) {
    dsl.deleteFrom(SECURITY_LEVEL).where(SECURITY_LEVEL.ID.eq(id)).execute();
  }

  @Override
  public Map<Long, Long> countDatasetsByLevel() {
    return dsl.select(DATASET.SECURITY_LEVEL_ID, DSL.count())
        .from(DATASET)
        .groupBy(DATASET.SECURITY_LEVEL_ID)
        .fetchMap(DATASET.SECURITY_LEVEL_ID, r -> r.get(1, Long.class));
  }

  @Override
  public Map<Long, Map<Long, Long>> countRoleGrantedDatasetsByLevelAndRole() {
    Map<Long, Map<Long, Long>> result = new java.util.HashMap<>();
    dsl.select(DATASET.SECURITY_LEVEL_ID, DATASET_ACCESS_GRANT.ROLE_ID, DSL.count())
        .from(DATASET_ACCESS_GRANT)
        .join(DATASET)
        .on(DATASET.ID.eq(DATASET_ACCESS_GRANT.DATASET_ID))
        .where(DATASET_ACCESS_GRANT.ROLE_ID.isNotNull())
        .groupBy(DATASET.SECURITY_LEVEL_ID, DATASET_ACCESS_GRANT.ROLE_ID)
        .forEach(
            r ->
                result
                    .computeIfAbsent(r.get(0, Long.class), k -> new java.util.HashMap<>())
                    .put(r.get(1, Long.class), r.get(2, Long.class)));
    return result;
  }

  @Override
  public Map<Long, Long> countRolesByLevel() {
    return dsl.select(ROLE.MAX_SECURITY_LEVEL_ID, DSL.count())
        .from(ROLE)
        .groupBy(ROLE.MAX_SECURITY_LEVEL_ID)
        .fetchMap(ROLE.MAX_SECURITY_LEVEL_ID, r -> r.get(1, Long.class));
  }

  @Override
  public int moveDatasets(long fromLevelId, long toLevelId) {
    return dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, toLevelId)
        .where(DATASET.SECURITY_LEVEL_ID.eq(fromLevelId))
        .execute();
  }

  @Override
  public int moveRoles(long fromLevelId, long toLevelId) {
    return dsl.update(ROLE)
        .set(ROLE.MAX_SECURITY_LEVEL_ID, toLevelId)
        .where(ROLE.MAX_SECURITY_LEVEL_ID.eq(fromLevelId))
        .execute();
  }

  @Override
  public void syncSystemAdminToTop() {
    long top = findTop().id();
    dsl.update(ROLE).set(ROLE.MAX_SECURITY_LEVEL_ID, top).where(SYSTEM_ADMIN_ROLE).execute();
  }

  @Override
  public Optional<RoleClearanceRow> findRoleClearanceRow(long roleId) {
    var r = dsl.selectFrom(ROLE).where(ROLE.ID.eq(roleId)).fetchOne();
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new RoleClearanceRow(r.getName(), r.getIsSystem(), r.getMaxSecurityLevelId()));
  }

  @Override
  public long countRoleMembers(long roleId) {
    return dsl.fetchCount(USER_ROLE, USER_ROLE.ROLE_ID.eq(roleId));
  }

  @Override
  public void updateRoleMaxLevel(long roleId, long levelId) {
    dsl.update(ROLE).set(ROLE.MAX_SECURITY_LEVEL_ID, levelId).where(ROLE.ID.eq(roleId)).execute();
  }

  @Override
  public long countDatasets(DatasetVisibility v) {
    return dsl.fetchCount(
        DATASET, JooqDatasetVisibility.condition(v, DATASET.ID, DATASET.SECURITY_LEVEL_ID));
  }

  @Override
  public Set<Long> findDistinctLevelIds(Collection<Long> datasetIds) {
    return dsl.selectDistinct(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.in(datasetIds))
        .fetchSet(DATASET.SECURITY_LEVEL_ID);
  }

  @Override
  public boolean isTempDataset(long datasetId) {
    return dsl.fetchExists(DATASET, DATASET.ID.eq(datasetId).and(DATASET.ORIGIN_TYPE.eq("TEMP")));
  }

  @Override
  public boolean isTempDatasetOfStep(long datasetId, long stepId) {
    return dsl.fetchExists(
        DATASET,
        DATASET
            .ID
            .eq(datasetId)
            .and(DATASET.ORIGIN_TYPE.eq("TEMP"))
            .and(DATASET.SOURCE_PIPELINE_STEP_ID.eq(stepId)));
  }

  @Override
  public List<RoleLevel> findRoleLevels() {
    return dsl.select(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID, ROLE.NAME, ROLE.IS_SYSTEM)
        .from(ROLE)
        .fetch(
            r ->
                new RoleLevel(
                    r.get(ROLE.ID),
                    r.get(ROLE.NAME),
                    r.get(ROLE.MAX_SECURITY_LEVEL_ID),
                    SystemAdminRole.matches(r.get(ROLE.NAME), r.get(ROLE.IS_SYSTEM))));
  }

  @Override
  public void setDatasetLevel(long datasetId, Long levelId) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, levelId)
        .where(DATASET.ID.eq(datasetId))
        .execute();
  }

  @Override
  public void setDatasetLevelClearingAutoRaise(long datasetId, long levelId) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, levelId)
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, (LocalDateTime) null)
        .where(DATASET.ID.eq(datasetId))
        .execute();
  }

  @Override
  public void setPipelineOutputLevel(long datasetId, long levelId, boolean autoRaise) {
    var update = dsl.update(DATASET).set(DATASET.SECURITY_LEVEL_ID, levelId);
    if (autoRaise) {
      update = update.set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, LocalDateTime.now());
    }
    update.where(DATASET.ID.eq(datasetId)).execute();
  }

  @Override
  public LocalDateTime findAutoRaisedAt(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .fetchOne(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT);
  }

  @Override
  public void clearAutoRaisedAt(long datasetId) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, (LocalDateTime) null)
        .where(DATASET.ID.eq(datasetId))
        .execute();
  }

  @Override
  public void restoreAutoRaisedAtIfAbsent(long datasetId, LocalDateTime previousAutoRaisedAt) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, previousAutoRaisedAt)
        .where(DATASET.ID.eq(datasetId))
        .and(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT.isNull())
        .execute();
  }

  @Override
  public boolean lockDatasetRow(long datasetId) {
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public Optional<Long> lockDatasetAndFindLevelId(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .forUpdate()
        .fetchOptional(DATASET.SECURITY_LEVEL_ID);
  }

  @Override
  public Map<Long, Long> findRoleLevelIds(Collection<Long> roleIds) {
    return dsl.select(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID)
        .from(ROLE)
        .where(ROLE.ID.in(roleIds))
        .fetchMap(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID);
  }

  @Override
  public boolean roleExists(long roleId) {
    return dsl.fetchExists(ROLE, ROLE.ID.eq(roleId));
  }

  @Override
  public List<CandidateUser> findCandidateUsers(long tenantId) {
    return dsl.select(USER.ID, USER.NAME, USER.EMAIL)
        .from(USER)
        .join(ActiveMembership.TABLE)
        .on(ActiveMembership.of(USER.ID, val(tenantId)))
        .where(USER.IS_ACTIVE.isTrue())
        .orderBy(USER.NAME.asc())
        .fetch(r -> new CandidateUser(r.get(USER.ID), r.get(USER.NAME), r.get(USER.EMAIL)));
  }

  @Override
  public List<CandidateRole> findCandidateRoles() {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.MAX_SECURITY_LEVEL_ID)
        .from(ROLE)
        .orderBy(ROLE.NAME.asc())
        .fetch(
            r ->
                new CandidateRole(
                    r.get(ROLE.ID), r.get(ROLE.NAME), r.get(ROLE.MAX_SECURITY_LEVEL_ID)));
  }

  @Override
  public boolean isActiveMember(long userId, long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(ActiveMembership.TABLE)
            .where(ActiveMembership.of(val(userId), val(tenantId)))
            // 후보 조회와 같은 기준: 비활성화된 계정은 멤버십이 ACTIVE 여도 대상이 아니다.
            .and(
                DSL.exists(
                    dsl.selectOne()
                        .from(USER)
                        .where(USER.ID.eq(userId))
                        .and(USER.IS_ACTIVE.isTrue()))));
  }

  @Override
  public void updateRanks(Map<Long, Integer> newRanks) {
    dsl.execute("SET CONSTRAINTS uq_security_level_rank DEFERRED");
    newRanks.forEach(
        (id, rank) ->
            dsl.update(SECURITY_LEVEL)
                .set(SECURITY_LEVEL.RANK, rank)
                .where(SECURITY_LEVEL.ID.eq(id))
                .execute());
  }

  /** DB 행 → 판정용 값 객체. 문자열 정책은 enum 으로 엄격 변환(CHECK 제약과 같은 집합). */
  public static LevelPolicy toPolicy(SecurityLevelRecord r) {
    return new LevelPolicy(
        r.getId(),
        r.getName(),
        r.getRank(),
        Boolean.TRUE.equals(r.getIsDefault()),
        Boolean.TRUE.equals(r.getAllowlistRequired()),
        Boolean.TRUE.equals(r.getAdminBypass()),
        LevelPolicy.ExportPolicy.valueOf(r.getExportPolicy()),
        LevelPolicy.AiPolicy.valueOf(r.getAiPolicy()),
        LevelPolicy.SharePolicy.valueOf(r.getSharePolicy()),
        Boolean.TRUE.equals(r.getAuditAccess()));
  }
}
