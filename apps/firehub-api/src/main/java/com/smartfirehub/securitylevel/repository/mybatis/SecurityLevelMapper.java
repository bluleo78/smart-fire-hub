package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.CandidateRole;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.CandidateUser;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.RoleClearanceRow;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.LevelCount;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.LevelRoleCount;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.RankEntry;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.RoleLevelId;
import com.smartfirehub.securitylevel.repository.mybatis.SecurityLevelRows.RoleRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 보안 등급·등급 배정·역할 자격·허용 목록 후보 MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/securitylevel/SecurityLevelMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)과 tenant_id DEFAULT
 * 가 맡고, 정책 없는 membership·사용자 조인만 인자로 받은 tenantId 를 명시한다.
 */
@PlatformMapper
public interface SecurityLevelMapper {

  /** 현재 테넌트 등급 전부(rank 오름차순). */
  List<LevelPolicy> selectAll();

  /** id 로 등급 한 건. 없으면 null. */
  LevelPolicy selectById(@Param("id") long id);

  /** 기본 등급. 없으면 null. */
  LevelPolicy selectDefault();

  /** 최상위(rank 최대) 등급. 없으면 null. */
  LevelPolicy selectTop();

  /** 데이터셋의 등급 id. 데이터셋이 없으면 null. */
  Long selectDatasetLevelId(@Param("datasetId") long datasetId);

  /**
   * 주어진 데이터셋(500 개 청크) 중 등급 플래그가 켜진 것.
   *
   * @param audited true 면 audit_access, false 면 allowlist_required 를 본다
   */
  List<Long> selectDatasetIdsWhereLevelFlag(
      @Param("chunks") List<List<Long>> chunks, @Param("audited") boolean audited);

  /** 이름이 같은 등급 수(excludeId 가 있으면 그 행 제외). */
  long countByName(@Param("name") String name, @Param("excludeId") Long excludeId);

  /** 등급을 넣고 생성 id 를 {@code row.id} 에 채운다. */
  int insert(SecurityLevelInsert row);

  /** 이름·플래그·정책·수정자·수정 시각을 바꾼다. */
  int update(
      @Param("id") long id,
      @Param("name") String name,
      @Param("allowlistRequired") boolean allowlistRequired,
      @Param("adminBypass") boolean adminBypass,
      @Param("exportPolicy") String exportPolicy,
      @Param("aiPolicy") String aiPolicy,
      @Param("sharePolicy") String sharePolicy,
      @Param("auditAccess") boolean auditAccess,
      @Param("actor") long actor,
      @Param("now") LocalDateTime now);

  /** 켜진 기본 등급을 모두 끈다. */
  int clearDefault();

  /** 등급 하나를 기본으로 켜고 켠 행 수를 돌려준다. */
  int markDefault(@Param("id") long id);

  /** 등급을 지운다. */
  int delete(@Param("id") long id);

  /** 등급별 데이터셋 수. */
  List<LevelCount> countDatasetsByLevel();

  /** 등급 × 역할별 역할 허용 데이터셋 수(사용자 항목 제외). */
  List<LevelRoleCount> countRoleGrantedDatasetsByLevelAndRole();

  /** 역할 최대 등급별 역할 수. */
  List<LevelCount> countRolesByLevel();

  /** 데이터셋 등급을 일괄로 옮긴다. */
  int moveDatasets(@Param("fromLevelId") long fromLevelId, @Param("toLevelId") long toLevelId);

  /** 역할 최대 등급을 일괄로 옮긴다. */
  int moveRoles(@Param("fromLevelId") long fromLevelId, @Param("toLevelId") long toLevelId);

  /** 시스템 역할 중 이름이 adminName 인 것의 최대 등급을 levelId 로 바꾼다. */
  int updateSystemAdminLevel(@Param("adminName") String adminName, @Param("levelId") long levelId);

  /** 역할 한 행(이름·시스템 여부·최대 등급). 없으면 null. */
  RoleClearanceRow selectRoleClearanceRow(@Param("roleId") long roleId);

  /** 역할 보유 사용자 수. */
  long countRoleMembers(@Param("roleId") long roleId);

  /** 역할의 최대 열람 등급을 바꾼다. */
  int updateRoleMaxLevel(@Param("roleId") long roleId, @Param("levelId") long levelId);

  /** 가시성으로 볼 수 있는 데이터셋 수. */
  long countVisibleDatasets(@Param("vis") VisibilityParams vis);

  /** 주어진 데이터셋(청크)의 등급 id(중복 제거). */
  List<Long> selectDistinctLevelIds(@Param("chunks") List<List<Long>> chunks);

  /** TEMP 데이터셋 수(stepId 가 있으면 그 스텝이 만든 것만). */
  long countTempDataset(@Param("datasetId") long datasetId, @Param("stepId") Long stepId);

  /** 현재 테넌트 역할 전부(id·이름·최대 등급·시스템 여부). */
  List<RoleRow> selectRoleRows();

  /** 데이터셋 등급을 바꾼다(자동 상향 표시는 그대로). */
  int setDatasetLevel(@Param("datasetId") long datasetId, @Param("levelId") Long levelId);

  /** 데이터셋 등급을 바꾸고 자동 상향 표시를 지운다. */
  int setDatasetLevelClearingAutoRaise(
      @Param("datasetId") long datasetId, @Param("levelId") long levelId);

  /** 데이터셋 등급을 바꾸고, raisedAt 이 있으면 자동 상향 시각도 남긴다. */
  int setPipelineOutputLevel(
      @Param("datasetId") long datasetId,
      @Param("levelId") long levelId,
      @Param("raisedAt") LocalDateTime raisedAt);

  /** 데이터셋의 자동 상향 시각(없으면 null). */
  LocalDateTime selectAutoRaisedAt(@Param("datasetId") long datasetId);

  /** 자동 상향 표시를 지운다. */
  int clearAutoRaisedAt(@Param("datasetId") long datasetId);

  /** 자동 상향 표시가 비어 있을 때만 previous 로 채운다. */
  int restoreAutoRaisedAtIfAbsent(
      @Param("datasetId") long datasetId, @Param("previous") LocalDateTime previous);

  /** 데이터셋 행을 FOR UPDATE 로 잠그고 등급 id 를 돌려준다(없으면 null). */
  Long lockDatasetAndSelectLevelId(@Param("datasetId") long datasetId);

  /** 주어진 역할(청크)의 최대 등급 id. */
  List<RoleLevelId> selectRoleLevelIds(@Param("chunks") List<List<Long>> chunks);

  /** 현재 테넌트(RLS)에서 그 역할 수(0 또는 1). */
  long countRole(@Param("roleId") long roleId);

  /** 이 테넌트 ACTIVE 멤버이면서 활성 계정인 사용자(이름·id 순). */
  List<CandidateUser> selectCandidateUsers(@Param("tenantId") long tenantId);

  /** 현재 테넌트 역할 전부(이름 순). */
  List<CandidateRole> selectCandidateRoles();

  /** 그 사용자가 이 테넌트 ACTIVE 멤버이고 활성 계정이면 1. */
  long countActiveMember(@Param("userId") long userId, @Param("tenantId") long tenantId);

  /** 여러 등급의 rank 를 한 문장(CASE)으로 바꾼다. */
  int updateRanks(@Param("entries") List<RankEntry> entries);
}
