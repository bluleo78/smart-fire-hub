package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;

import com.smartfirehub.jooq.tables.records.SecurityLevelRecord;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
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

  /** 기본 등급(테넌트당 정확히 1개). */
  public Optional<LevelPolicy> findDefault() {
    return dsl.selectFrom(SECURITY_LEVEL)
        .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
        .fetchOptional(SecurityLevelRepository::toPolicy);
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

  /** 기본 등급 교체 — 부분 UNIQUE 때문에 기존 기본을 먼저 끈다(같은 트랜잭션). */
  public void setDefault(long id) {
    dsl.update(SECURITY_LEVEL)
        .set(SECURITY_LEVEL.IS_DEFAULT, false)
        .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
        .execute();
    dsl.update(SECURITY_LEVEL)
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
        .where(ROLE.NAME.eq("ADMIN").and(ROLE.IS_SYSTEM.isTrue()))
        .execute();
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
