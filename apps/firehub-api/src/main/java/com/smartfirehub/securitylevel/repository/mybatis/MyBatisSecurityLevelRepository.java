package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.SystemAdminRole;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.LevelCount;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.LevelRoleCount;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.RankEntry;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.RoleLevelId;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * SecurityLevelRepository 의 MyBatis 구현(WD-711) — {@code JooqSecurityLevelRepository} 와 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional}(REQUIRED) 은 jOOQ 구현 그대로다 — 테넌트 컨텍스트(PG GUC·Tibero 컨텍스트)는 트랜잭션이
 * 열릴 때 심기므로 트랜잭션 없는 호출자(비동기 러너·인터셉터)도 안전하고, 호출자 트랜잭션이 있으면 합류한다. readOnly 로 두지 않은 이유: 잠금 메서드(FOR
 * UPDATE·advisory)와 쓰기 메서드가 많아 PG 가 읽기 전용 트랜잭션에서 거절할 경로가 생긴다.
 *
 * <p>등급 행은 매퍼 결과 매핑이 곧바로 {@link LevelPolicy} 로 만든다(jOOQ 의 {@code toPolicy} 와 같은 엄격한 enum 변환). 순서 일괄
 * 갱신은 한 문장 CASE 로 한다 — Tibero 는 제약 지연이 안 된다(매퍼 XML 주석).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisSecurityLevelRepository implements SecurityLevelRepository {

  private final SecurityLevelMapper mapper;

  /** 등급 정의 변경 직렬화 잠금 — jOOQ 구현과 같은 플랫폼 잠금 추상화(PG advisory·Tibero DBMS_LOCK). */
  private final PlatformLocks locks;

  public MyBatisSecurityLevelRepository(SecurityLevelMapper mapper, PlatformLocks locks) {
    this.mapper = mapper;
    this.locks = locks;
  }

  @Override
  public List<LevelPolicy> findAll() {
    return mapper.selectAll();
  }

  @Override
  public Optional<LevelPolicy> findById(long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  /** 등급 id → rank. 같은 테넌트 등급은 id 가 유일하므로 키 충돌이 없다. */
  @Override
  public Map<Long, Integer> rankById() {
    Map<Long, Integer> result = new HashMap<>();
    for (LevelPolicy p : findAll()) {
      result.put(p.id(), p.rank());
    }
    return result;
  }

  /** 데이터셋이 없으면 예외 — jOOQ 의 fetchSingle 과 같이 "있다고 확인된 데이터셋에만 쓴다"는 계약을 지킨다. */
  @Override
  public Long findDatasetLevelId(long datasetId) {
    Long levelId = mapper.selectDatasetLevelId(datasetId);
    if (levelId == null) {
      throw new EmptyResultDataAccessException("데이터셋이 없습니다: " + datasetId, 1);
    }
    return levelId;
  }

  @Override
  public Set<Long> findAuditedDatasetIds(Collection<Long> datasetIds) {
    return findDatasetIdsWhereLevelFlag(datasetIds, true);
  }

  @Override
  public Set<Long> findAllowlistRequiredDatasetIds(Collection<Long> datasetIds) {
    return findDatasetIdsWhereLevelFlag(datasetIds, false);
  }

  /** 등급 플래그별 필터 조회의 공통 본문 — 빈 입력은 조회 없이 빈 집합(jOOQ 와 같다), id 는 500 개씩 청크. */
  private Set<Long> findDatasetIdsWhereLevelFlag(Collection<Long> datasetIds, boolean audited) {
    if (datasetIds.isEmpty()) {
      return new HashSet<>();
    }
    return new HashSet<>(mapper.selectDatasetIdsWhereLevelFlag(InChunks.of(datasetIds), audited));
  }

  @Override
  public Optional<LevelPolicy> findDefault() {
    return Optional.ofNullable(mapper.selectDefault());
  }

  /** jOOQ 와 같은 키로 트랜잭션 잠금을 건다 — 호출자 트랜잭션이 끝날 때 풀린다. */
  @Override
  public void lockTenantLevelDefinitions(long tenantId) {
    locks.xactLock("security_level_defs_t" + tenantId);
  }

  /** 최상위 등급 — 테넌트에는 늘 등급이 있다. 없으면 jOOQ fetchSingle 처럼 예외. */
  @Override
  public LevelPolicy findTop() {
    LevelPolicy top = mapper.selectTop();
    if (top == null) {
      throw new EmptyResultDataAccessException("보안 등급이 없습니다", 1);
    }
    return top;
  }

  @Override
  public boolean existsByName(String name, Long excludeId) {
    return mapper.countByName(name.trim(), excludeId) > 0;
  }

  @Override
  public long insert(SecurityLevelRequest req, int rank, long actor) {
    SecurityLevelInsert row =
        new SecurityLevelInsert(
            rank,
            req.name().trim(),
            req.allowlistRequired(),
            req.adminBypass(),
            req.exportPolicy().name(),
            req.aiPolicy().name(),
            req.sharePolicy().name(),
            req.auditAccess(),
            actor);
    mapper.insert(row);
    return row.getId();
  }

  /** 수정 시각은 jOOQ 처럼 자바 현재 시각으로 남긴다. */
  @Override
  public void update(long id, SecurityLevelRequest req, long actor) {
    mapper.update(
        id,
        req.name().trim(),
        req.allowlistRequired(),
        req.adminBypass(),
        req.exportPolicy().name(),
        req.aiPolicy().name(),
        req.sharePolicy().name(),
        req.auditAccess(),
        actor,
        LocalDateTime.now());
  }

  /** 부분 UNIQUE(테넌트당 기본 1개) 때문에 기존 기본을 먼저 끄고 켠다 — 같은 트랜잭션. */
  @Override
  public int setDefault(long id) {
    mapper.clearDefault();
    return mapper.markDefault(id);
  }

  @Override
  public void delete(long id) {
    mapper.delete(id);
  }

  @Override
  public Map<Long, Long> countDatasetsByLevel() {
    return toMap(mapper.countDatasetsByLevel());
  }

  @Override
  public Map<Long, Map<Long, Long>> countRoleGrantedDatasetsByLevelAndRole() {
    Map<Long, Map<Long, Long>> result = new HashMap<>();
    for (LevelRoleCount r : mapper.countRoleGrantedDatasetsByLevelAndRole()) {
      result.computeIfAbsent(r.levelId(), k -> new HashMap<>()).put(r.roleId(), r.cnt());
    }
    return result;
  }

  @Override
  public Map<Long, Long> countRolesByLevel() {
    return toMap(mapper.countRolesByLevel());
  }

  /** 집계 행 → 맵(키 null 도 jOOQ fetchMap 처럼 그대로 담는다). */
  private static Map<Long, Long> toMap(List<LevelCount> rows) {
    Map<Long, Long> result = new HashMap<>();
    for (LevelCount r : rows) {
      result.put(r.levelId(), r.cnt());
    }
    return result;
  }

  @Override
  public int moveDatasets(long fromLevelId, long toLevelId) {
    return mapper.moveDatasets(fromLevelId, toLevelId);
  }

  @Override
  public int moveRoles(long fromLevelId, long toLevelId) {
    return mapper.moveRoles(fromLevelId, toLevelId);
  }

  /** 시스템 ADMIN 을 현재 최상위 등급으로 — 판정 이름은 SystemAdminRole.NAME 하나를 공유한다. */
  @Override
  public void syncSystemAdminToTop() {
    mapper.updateSystemAdminLevel(SystemAdminRole.NAME, findTop().id());
  }

  @Override
  public Optional<RoleClearanceRow> findRoleClearanceRow(long roleId) {
    return Optional.ofNullable(mapper.selectRoleClearanceRow(roleId));
  }

  @Override
  public long countRoleMembers(long roleId) {
    return mapper.countRoleMembers(roleId);
  }

  @Override
  public void updateRoleMaxLevel(long roleId, long levelId) {
    mapper.updateRoleMaxLevel(roleId, levelId);
  }

  /** 가시성 술어는 공유 조각(DatasetVisibility.visible)으로 렌더한다 — jOOQ 렌더러와 같은 의미. */
  @Override
  public long countDatasets(DatasetVisibility v) {
    return mapper.countVisibleDatasets(MyBatisDatasetVisibility.params(v));
  }

  /** 빈 입력은 jOOQ 의 빈 IN(거짓)과 같이 빈 집합. */
  @Override
  public Set<Long> findDistinctLevelIds(Collection<Long> datasetIds) {
    if (datasetIds.isEmpty()) {
      return new HashSet<>();
    }
    return new HashSet<>(mapper.selectDistinctLevelIds(InChunks.of(datasetIds)));
  }

  @Override
  public boolean isTempDataset(long datasetId) {
    return mapper.countTempDataset(datasetId, null) > 0;
  }

  @Override
  public boolean isTempDatasetOfStep(long datasetId, long stepId) {
    return mapper.countTempDataset(datasetId, stepId) > 0;
  }

  /** 시스템 ADMIN 여부는 SQL 이 아니라 SystemAdminRole.matches 로 판정한다(jOOQ 와 같은 규칙 한 곳). */
  @Override
  public List<RoleLevel> findRoleLevels() {
    List<RoleLevel> result = new ArrayList<>();
    for (SecurityLevelRows.RoleRow r : mapper.selectRoleRows()) {
      result.add(
          new RoleLevel(
              r.id(), r.name(), r.levelId(), SystemAdminRole.matches(r.name(), r.isSystem())));
    }
    return result;
  }

  @Override
  public void setDatasetLevel(long datasetId, Long levelId) {
    mapper.setDatasetLevel(datasetId, levelId);
  }

  @Override
  public void setDatasetLevelClearingAutoRaise(long datasetId, long levelId) {
    mapper.setDatasetLevelClearingAutoRaise(datasetId, levelId);
  }

  /** 자동 상향이면 상향 시각을 자바 현재 시각으로 남긴다(jOOQ 와 같다). */
  @Override
  public void setPipelineOutputLevel(long datasetId, long levelId, boolean autoRaise) {
    mapper.setPipelineOutputLevel(datasetId, levelId, autoRaise ? LocalDateTime.now() : null);
  }

  @Override
  public LocalDateTime findAutoRaisedAt(long datasetId) {
    return mapper.selectAutoRaisedAt(datasetId);
  }

  @Override
  public void clearAutoRaisedAt(long datasetId) {
    mapper.clearAutoRaisedAt(datasetId);
  }

  @Override
  public void restoreAutoRaisedAtIfAbsent(long datasetId, LocalDateTime previousAutoRaisedAt) {
    mapper.restoreAutoRaisedAtIfAbsent(datasetId, previousAutoRaisedAt);
  }

  @Override
  public boolean lockDatasetRow(long datasetId) {
    return mapper.lockDatasetAndSelectLevelId(datasetId) != null;
  }

  @Override
  public Optional<Long> lockDatasetAndFindLevelId(long datasetId) {
    return Optional.ofNullable(mapper.lockDatasetAndSelectLevelId(datasetId));
  }

  /** 빈 입력은 빈 맵(jOOQ 의 빈 IN 과 같다), id 는 500 개씩 청크. */
  @Override
  public Map<Long, Long> findRoleLevelIds(Collection<Long> roleIds) {
    Map<Long, Long> result = new HashMap<>();
    if (roleIds.isEmpty()) {
      return result;
    }
    for (RoleLevelId r : mapper.selectRoleLevelIds(InChunks.of(roleIds))) {
      result.put(r.roleId(), r.levelId());
    }
    return result;
  }

  @Override
  public boolean roleExists(long roleId) {
    return mapper.countRole(roleId) > 0;
  }

  @Override
  public List<CandidateUser> findCandidateUsers(long tenantId) {
    return mapper.selectCandidateUsers(tenantId);
  }

  @Override
  public List<CandidateRole> findCandidateRoles() {
    return mapper.selectCandidateRoles();
  }

  @Override
  public boolean isActiveMember(long userId, long tenantId) {
    return mapper.countActiveMember(userId, tenantId) > 0;
  }

  /** 한 문장 CASE 로 rank 를 바꿔 (tenant_id, rank) 유일성을 문장 끝에서만 보게 한다. 빈 맵이면 아무것도 하지 않는다. */
  @Override
  public void updateRanks(Map<Long, Integer> newRanks) {
    if (newRanks.isEmpty()) {
      return;
    }
    List<RankEntry> entries = new ArrayList<>();
    newRanks.forEach((id, rank) -> entries.add(new RankEntry(id, rank)));
    mapper.updateRanks(entries);
  }
}
