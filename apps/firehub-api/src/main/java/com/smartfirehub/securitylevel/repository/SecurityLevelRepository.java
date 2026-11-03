package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static com.smartfirehub.jooq.Tables.USER_ROLE;
import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.jooq.tables.records.SecurityLevelRecord;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * security_level 접근. 테넌트 범위는 RLS(GUC)가 정한다 — GUC 는 트랜잭션 시작 때만 주입되므로 클래스 레벨 @Transactional 로 "트랜잭션
 * 없는 호출자"(비동기 러너·인터셉터)도 안전하게 한다(DatasetRepository 와 같은 관례). 호출자 트랜잭션이 있으면 합류한다.
 */
@Repository
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class SecurityLevelRepository {

  private final DSLContext dsl;

  /** 등급 정의 변경 직렬화 잠금 — PG advisory 호출을 플랫폼 잠금 추상화로 위임한다(WD-486). */
  private final PlatformLocks locks;

  /** 현재 테넌트의 등급 전부(rank 오름차순 — 화면의 "아래로 갈수록 높음" 순서). */
  public List<LevelPolicy> findAll() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .orderBy(SECURITY_LEVEL.RANK.asc())
        .fetch(SecurityLevelRepository::toPolicy);
  }

  public Optional<LevelPolicy> findById(long id) {
    return dsl.selectFrom(SECURITY_LEVEL)
        .where(SECURITY_LEVEL.ID.eq(id))
        .fetchOptional(SecurityLevelRepository::toPolicy);
  }

  /** 등급 id → rank(현재 테넌트 전부). 자격·시드 계산이 등급 id 를 rank 로 바꿀 때 쓴다. */
  public Map<Long, Integer> rankById() {
    return findAll().stream().collect(Collectors.toMap(LevelPolicy::id, LevelPolicy::rank));
  }

  /** 데이터셋 1개의 현재 등급 id. 데이터셋이 없으면 예외(fetchSingle) — 호출자는 존재가 확인된 데이터셋에만 쓴다. */
  public Long findDatasetLevelId(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .fetchSingle(DATASET.SECURITY_LEVEL_ID);
  }

  /** 주어진 데이터셋 중 등급의 audit_access 가 켜진 것(스펙 §4.6 — 감사 등급 접근 기록 대상). 접근 감사 기록기가 기록 대상을 거를 때 쓴다. */
  public Set<Long> findAuditedDatasetIds(Collection<Long> datasetIds) {
    return findDatasetIdsWhereLevel(datasetIds, SECURITY_LEVEL.AUDIT_ACCESS.isTrue());
  }

  /** 주어진 데이터셋 중 등급이 allowlist_required 인 것(WD-30 — 파이프라인 출력 허용 목록 시드는 이 입력들의 허용 항목 교집합으로 계산한다). */
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

  /** 기본 등급(테넌트당 정확히 1개). */
  public Optional<LevelPolicy> findDefault() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
        .fetchOptional(SecurityLevelRepository::toPolicy);
  }

  /**
   * 이 테넌트의 등급 정의 변경(생성·삭제·순서)을 직렬화하는 트랜잭션 단위 advisory 잠금(커밋·롤백 때 풀린다). 호출자 트랜잭션에 합류해야 의미가 있다 — 트랜잭션
   * 없이 부르면 이 메서드의 트랜잭션이 끝나며 곧바로 풀린다.
   *
   * <p>왜(CR1): 등급 수 상한 검사(count)와 다음 rank(최상위+1) 계산이 잠금 없이 분리돼 있으면 동시 생성이 같은 상태를 읽는다. 키는 PYTHON 읽기
   * 동기화 잠금(python_read_sync_t*)과 다르게 둔다 — 등급 변경 트랜잭션이 커밋 후 동기화를 기다리게 하지 않는다.
   */
  public void lockTenantLevelDefinitions(long tenantId) {
    locks.xactLock("security_level_defs_t" + tenantId);
  }

  /** 최상위 등급(rank 최대). 테넌트에는 항상 1개 이상 있다(기본 등급 삭제 불가). */
  public LevelPolicy findTop() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .orderBy(SECURITY_LEVEL.RANK.desc())
        .limit(1)
        .fetchSingle(SecurityLevelRepository::toPolicy);
  }

  /** 이름 중복 여부(같은 테넌트, excludeId 제외). UNIQUE 위반을 500 이 아닌 의미 있는 409 로 바꾸려는 사전 검사. */
  public boolean existsByName(String name, Long excludeId) {
    var cond = SECURITY_LEVEL.NAME.eq(name.trim());
    if (excludeId != null) {
      cond = cond.and(SECURITY_LEVEL.ID.ne(excludeId));
    }
    return dsl.fetchExists(SECURITY_LEVEL, cond);
  }

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

  /**
   * 기본 등급 교체 — 부분 UNIQUE 때문에 기존 기본을 먼저 끈다(같은 트랜잭션). 켠 행 수를 돌려준다 — 0 이면 등급이 없어 기본이 하나도 없게 되므로 호출자가
   * 롤백해야 한다(WD-400).
   */
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

  public void delete(long id) {
    dsl.deleteFrom(SECURITY_LEVEL).where(SECURITY_LEVEL.ID.eq(id)).execute();
  }

  /** 등급별 데이터셋 수. */
  public Map<Long, Long> countDatasetsByLevel() {
    return dsl.select(DATASET.SECURITY_LEVEL_ID, DSL.count())
        .from(DATASET)
        .groupBy(DATASET.SECURITY_LEVEL_ID)
        .fetchMap(DATASET.SECURITY_LEVEL_ID, r -> r.get(1, Long.class));
  }

  /**
   * 등급별 · 역할별 "역할 허용 항목이 있는 데이터셋" 수 — 순서 변경 영향 미리보기용(그룹 쿼리 1회, N+1 없음). 사용자 단위 항목(user_id)은 역할에
   * 귀속시키지 않는다.
   */
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

  /** 등급별 역할 수(역할의 최대 열람 등급 기준). */
  public Map<Long, Long> countRolesByLevel() {
    return dsl.select(ROLE.MAX_SECURITY_LEVEL_ID, DSL.count())
        .from(ROLE)
        .groupBy(ROLE.MAX_SECURITY_LEVEL_ID)
        .fetchMap(ROLE.MAX_SECURITY_LEVEL_ID, r -> r.get(1, Long.class));
  }

  public int moveDatasets(long fromLevelId, long toLevelId) {
    return dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, toLevelId)
        .where(DATASET.SECURITY_LEVEL_ID.eq(fromLevelId))
        .execute();
  }

  public int moveRoles(long fromLevelId, long toLevelId) {
    return dsl.update(ROLE)
        .set(ROLE.MAX_SECURITY_LEVEL_ID, toLevelId)
        .where(ROLE.MAX_SECURITY_LEVEL_ID.eq(fromLevelId))
        .execute();
  }

  /** 판단 사항 13 — 시스템 ADMIN 을 현재 최상위로. 등급 추가·순서 변경·삭제 직후 매번 부른다. */
  public void syncSystemAdminToTop() {
    long top = findTop().id();
    dsl.update(ROLE)
        .set(ROLE.MAX_SECURITY_LEVEL_ID, top)
        .where(SystemAdminRole.CONDITION)
        .execute();
  }

  /** 역할 자격 화면·변경 판정에 필요한 역할 한 행 — 생성 레코드를 서비스에 노출하지 않으려는 값 객체. */
  public record RoleClearanceRow(String name, Boolean isSystem, Long maxSecurityLevelId) {}

  /** 역할 1개의 (이름·시스템 여부·최대 열람 등급). 없으면 비어 돌아온다. */
  public Optional<RoleClearanceRow> findRoleClearanceRow(long roleId) {
    var r = dsl.selectFrom(ROLE).where(ROLE.ID.eq(roleId)).fetchOne();
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new RoleClearanceRow(r.getName(), r.getIsSystem(), r.getMaxSecurityLevelId()));
  }

  /** 이 역할을 보유한 사용자 수. */
  public long countRoleMembers(long roleId) {
    return dsl.fetchCount(USER_ROLE, USER_ROLE.ROLE_ID.eq(roleId));
  }

  /** 역할의 최대 열람 등급을 바꾼다. */
  public void updateRoleMaxLevel(long roleId, long levelId) {
    dsl.update(ROLE).set(ROLE.MAX_SECURITY_LEVEL_ID, levelId).where(ROLE.ID.eq(roleId)).execute();
  }

  /**
   * 호출자가 조건 조립기(DatasetAccessGuard)로 만든 가시성 조건에 맞는 데이터셋 수.
   *
   * <p>조건은 데이터셋의 id·등급 컬럼에 거는 술어라, 서비스가 생성 테이블을 직접 참조하지 않도록 컬럼을 이 메서드가 넘겨 준다(조건 조립기 설계 변경은 WD-485
   * 범위).
   *
   * @param conditionOf (데이터셋 id 컬럼, 데이터셋 등급 컬럼) → 가시성 조건
   */
  public long countDatasets(BiFunction<Field<Long>, Field<Long>, Condition> conditionOf) {
    return dsl.fetchCount(DATASET, conditionOf.apply(DATASET.ID, DATASET.SECURITY_LEVEL_ID));
  }

  /** 주어진 데이터셋들이 속한 등급 id 의 집합(중복 제거). */
  public Set<Long> findDistinctLevelIds(Collection<Long> datasetIds) {
    return dsl.selectDistinct(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.in(datasetIds))
        .fetchSet(DATASET.SECURITY_LEVEL_ID);
  }

  /** 데이터셋이 러너가 만든 TEMP(origin_type='TEMP') 인가. */
  public boolean isTempDataset(long datasetId) {
    return dsl.fetchExists(DATASET, DATASET.ID.eq(datasetId).and(DATASET.ORIGIN_TYPE.eq("TEMP")));
  }

  /** 데이터셋이 이 스텝이 만든 TEMP(origin_type='TEMP' 이고 source_pipeline_step_id 가 이 스텝) 인가. */
  public boolean isTempDatasetOfStep(long datasetId, long stepId) {
    return dsl.fetchExists(
        DATASET,
        DATASET
            .ID
            .eq(datasetId)
            .and(DATASET.ORIGIN_TYPE.eq("TEMP"))
            .and(DATASET.SOURCE_PIPELINE_STEP_ID.eq(stepId)));
  }

  /** 역할 1개의 열람 등급 — 허용 목록 시드가 "rank 이상 자격 역할"을 고르는 데 쓴다. */
  public record RoleLevel(long roleId, String roleName, Long levelId, boolean systemAdmin) {}

  /** 현재 테넌트 모든 역할의 (역할 id·이름, 최대 열람 등급, 시스템 ADMIN 여부). */
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

  /**
   * 데이터셋의 보안 등급을 지정 값으로 바꾼다(clone 상속). 자동 상향 표시는 건드리지 않는다. 호출자(DatasetSecurityService)는 모두 서비스 트랜잭션
   * 안이다.
   */
  public void setDatasetLevel(long datasetId, Long levelId) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, levelId)
        .where(DATASET.ID.eq(datasetId))
        .execute();
  }

  /** 수동 등급 변경 — 등급을 바꾸면서 자동 상향 표시도 함께 지운다(남기면 하향 뒤에도 "자동 상향되었습니다" 배너가 남는다). */
  public void setDatasetLevelClearingAutoRaise(long datasetId, long levelId) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_ID, levelId)
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, (LocalDateTime) null)
        .where(DATASET.ID.eq(datasetId))
        .execute();
  }

  /**
   * 파이프라인 출력 등급 변경 — 등급을 바꾸고, 자동 상향이면 상향 시각(지금)도 남긴다.
   *
   * @param autoRaise true 면 자동 상향 시각을 현재 시각으로 기록
   */
  public void setPipelineOutputLevel(long datasetId, long levelId, boolean autoRaise) {
    var update = dsl.update(DATASET).set(DATASET.SECURITY_LEVEL_ID, levelId);
    if (autoRaise) {
      update = update.set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, LocalDateTime.now());
    }
    update.where(DATASET.ID.eq(datasetId)).execute();
  }

  /** 데이터셋의 자동 상향 시각. 상향된 적이 없으면(또는 데이터셋이 없으면) null. */
  public LocalDateTime findAutoRaisedAt(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .fetchOne(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT);
  }

  /** 데이터셋의 자동 상향 표시를 지운다. */
  public void clearAutoRaisedAt(long datasetId) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, (LocalDateTime) null)
        .where(DATASET.ID.eq(datasetId))
        .execute();
  }

  /** 자동 상향 표시가 비어 있을 때만 이전 시각으로 되살린다(이번 실행 중에 더 최근 표시가 찍혔으면 그 값을 둔다). */
  public void restoreAutoRaisedAtIfAbsent(long datasetId, LocalDateTime previousAutoRaisedAt) {
    dsl.update(DATASET)
        .set(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT, previousAutoRaisedAt)
        .where(DATASET.ID.eq(datasetId))
        .and(DATASET.SECURITY_LEVEL_AUTO_RAISED_AT.isNull())
        .execute();
  }

  /**
   * 데이터셋 행을 {@code FOR UPDATE} 로 잠근다(호출 트랜잭션 끝까지 유지). 호출 서비스 메서드가 모두 트랜잭션이라 클래스 단위 REQUIRED 가 합류한다.
   *
   * @return 데이터셋 행이 있어 잠갔으면 true
   */
  public boolean lockDatasetRow(long datasetId) {
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  /** 데이터셋 행을 {@code FOR UPDATE} 로 잠그면서 현재 등급 id 를 읽는다. 데이터셋이 없으면 비어 돌아온다. */
  public Optional<Long> lockDatasetAndFindLevelId(long datasetId) {
    return dsl.select(DATASET.SECURITY_LEVEL_ID)
        .from(DATASET)
        .where(DATASET.ID.eq(datasetId))
        .forUpdate()
        .fetchOptional(DATASET.SECURITY_LEVEL_ID);
  }

  /** 역할 id → 역할 열람 등급 id(max_security_level_id). 이 테넌트(RLS) 역할만 나온다. */
  public Map<Long, Long> findRoleLevelIds(Collection<Long> roleIds) {
    return dsl.select(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID)
        .from(ROLE)
        .where(ROLE.ID.in(roleIds))
        .fetchMap(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID);
  }

  /** 이 테넌트(RLS)에 그 역할이 있는가. */
  public boolean roleExists(long roleId) {
    return dsl.fetchExists(ROLE, ROLE.ID.eq(roleId));
  }

  /** 허용 목록 후보 사용자 한 명. */
  public record CandidateUser(Long id, String name, String email) {}

  /** 허용 목록 후보 역할 한 개(역할 열람 등급 포함). */
  public record CandidateRole(Long id, String name, Long levelId) {}

  /** 이 테넌트 ACTIVE 멤버이면서 활성 계정인 사용자(이름 순). membership 은 RLS 없는 전역 테이블이라 tenant_id 를 명시한다. */
  public List<CandidateUser> findCandidateUsers(long tenantId) {
    return dsl.select(USER.ID, USER.NAME, USER.EMAIL)
        .from(USER)
        .join(ActiveMembership.TABLE)
        .on(ActiveMembership.of(USER.ID, val(tenantId)))
        .where(USER.IS_ACTIVE.isTrue())
        .orderBy(USER.NAME.asc())
        .fetch(r -> new CandidateUser(r.get(USER.ID), r.get(USER.NAME), r.get(USER.EMAIL)));
  }

  /** 이 테넌트(RLS) 역할 전부(이름 순) — 허용 목록 후보용. */
  public List<CandidateRole> findCandidateRoles() {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.MAX_SECURITY_LEVEL_ID)
        .from(ROLE)
        .orderBy(ROLE.NAME.asc())
        .fetch(
            r ->
                new CandidateRole(
                    r.get(ROLE.ID), r.get(ROLE.NAME), r.get(ROLE.MAX_SECURITY_LEVEL_ID)));
  }

  /** 사용자가 이 테넌트 ACTIVE 멤버이고 계정도 활성인가(허용 항목 추가 검증 — 후보 조회와 같은 기준). */
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

  /** 순서 일괄 갱신 — UNIQUE(tenant_id, rank) 를 트랜잭션 끝까지 미룬다(DEFERRABLE). */
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
