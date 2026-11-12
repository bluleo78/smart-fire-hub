package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 보안 등급(security_level 테이블) 정의와 등급 배정(dataset·role 의 등급 컬럼)·허용 목록 집계(dataset_access_grant)·사용자
 * 역할(user_role) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqSecurityLevelRepository}(jOOQ/PostgreSQL, WD-485)와 {@code
 * MyBatisSecurityLevelRepository}(Tibero 설치본, WD-711) 이다.
 */
public interface SecurityLevelRepository {

  /** 현재 테넌트의 등급 전부(rank 오름차순 — 화면의 "아래로 갈수록 높음" 순서). */
  List<LevelPolicy> findAll();

  Optional<LevelPolicy> findById(long id);

  /** 등급 id → rank(현재 테넌트 전부). 자격·시드 계산이 등급 id 를 rank 로 바꿀 때 쓴다. */
  Map<Long, Integer> rankById();

  /** 데이터셋 1개의 현재 등급 id. 데이터셋이 없으면 예외(fetchSingle) — 호출자는 존재가 확인된 데이터셋에만 쓴다. */
  Long findDatasetLevelId(long datasetId);

  /** 주어진 데이터셋 중 등급의 audit_access 가 켜진 것(스펙 §4.6 — 감사 등급 접근 기록 대상). 접근 감사 기록기가 기록 대상을 거를 때 쓴다. */
  Set<Long> findAuditedDatasetIds(Collection<Long> datasetIds);

  /** 주어진 데이터셋 중 등급이 allowlist_required 인 것(WD-30 — 파이프라인 출력 허용 목록 시드는 이 입력들의 허용 항목 교집합으로 계산한다). */
  Set<Long> findAllowlistRequiredDatasetIds(Collection<Long> datasetIds);

  /** 기본 등급(테넌트당 정확히 1개). */
  Optional<LevelPolicy> findDefault();

  /**
   * 이 테넌트의 등급 정의 변경(생성·삭제·순서)을 직렬화하는 트랜잭션 단위 advisory 잠금(커밋·롤백 때 풀린다). 호출자 트랜잭션에 합류해야 의미가 있다 — 트랜잭션
   * 없이 부르면 이 메서드의 트랜잭션이 끝나며 곧바로 풀린다.
   *
   * <p>왜(CR1): 등급 수 상한 검사(count)와 다음 rank(최상위+1) 계산이 잠금 없이 분리돼 있으면 동시 생성이 같은 상태를 읽는다. 키는 PYTHON 읽기
   * 동기화 잠금(python_read_sync_t*)과 다르게 둔다 — 등급 변경 트랜잭션이 커밋 후 동기화를 기다리게 하지 않는다.
   */
  void lockTenantLevelDefinitions(long tenantId);

  /** 최상위 등급(rank 최대). 테넌트에는 항상 1개 이상 있다(기본 등급 삭제 불가). */
  LevelPolicy findTop();

  /** 이름 중복 여부(같은 테넌트, excludeId 제외). UNIQUE 위반을 500 이 아닌 의미 있는 409 로 바꾸려는 사전 검사. */
  boolean existsByName(String name, Long excludeId);

  long insert(SecurityLevelRequest req, int rank, long actor);

  void update(long id, SecurityLevelRequest req, long actor);

  /**
   * 기본 등급 교체 — 부분 UNIQUE 때문에 기존 기본을 먼저 끈다(같은 트랜잭션). 켠 행 수를 돌려준다 — 0 이면 등급이 없어 기본이 하나도 없게 되므로 호출자가
   * 롤백해야 한다(WD-400).
   */
  int setDefault(long id);

  void delete(long id);

  /** 등급별 데이터셋 수. */
  Map<Long, Long> countDatasetsByLevel();

  /**
   * 등급별 · 역할별 "역할 허용 항목이 있는 데이터셋" 수 — 순서 변경 영향 미리보기용(그룹 쿼리 1회, N+1 없음). 사용자 단위 항목(user_id)은 역할에
   * 귀속시키지 않는다.
   */
  Map<Long, Map<Long, Long>> countRoleGrantedDatasetsByLevelAndRole();

  /** 등급별 역할 수(역할의 최대 열람 등급 기준). */
  Map<Long, Long> countRolesByLevel();

  int moveDatasets(long fromLevelId, long toLevelId);

  int moveRoles(long fromLevelId, long toLevelId);

  /** 판단 사항 13 — 시스템 ADMIN 을 현재 최상위로. 등급 추가·순서 변경·삭제 직후 매번 부른다. */
  void syncSystemAdminToTop();

  /** 역할 자격 화면·변경 판정에 필요한 역할 한 행 — 생성 레코드를 서비스에 노출하지 않으려는 값 객체. */
  record RoleClearanceRow(String name, Boolean isSystem, Long maxSecurityLevelId) {}

  /** 역할 1개의 (이름·시스템 여부·최대 열람 등급). 없으면 비어 돌아온다. */
  Optional<RoleClearanceRow> findRoleClearanceRow(long roleId);

  /** 이 역할을 보유한 사용자 수. */
  long countRoleMembers(long roleId);

  /** 역할의 최대 열람 등급을 바꾼다. */
  void updateRoleMaxLevel(long roleId, long levelId);

  /**
   * 주어진 가시성으로 볼 수 있는 데이터셋 수. 가시성 술어는 데이터셋 테이블의 생성 컬럼(id·security_level_id)에 걸어 렌더한다 — 서비스가 생성 테이블을
   * 직접 참조하지 않도록 컬럼 선택은 이 메서드가 맡는다(WD-485).
   */
  long countDatasets(DatasetVisibility v);

  /** 주어진 데이터셋들이 속한 등급 id 의 집합(중복 제거). */
  Set<Long> findDistinctLevelIds(Collection<Long> datasetIds);

  /** 데이터셋이 러너가 만든 TEMP(origin_type='TEMP') 인가. */
  boolean isTempDataset(long datasetId);

  /** 데이터셋이 이 스텝이 만든 TEMP(origin_type='TEMP' 이고 source_pipeline_step_id 가 이 스텝) 인가. */
  boolean isTempDatasetOfStep(long datasetId, long stepId);

  /** 역할 1개의 열람 등급 — 허용 목록 시드가 "rank 이상 자격 역할"을 고르는 데 쓴다. */
  record RoleLevel(long roleId, String roleName, Long levelId, boolean systemAdmin) {}

  /** 현재 테넌트 모든 역할의 (역할 id·이름, 최대 열람 등급, 시스템 ADMIN 여부). */
  List<RoleLevel> findRoleLevels();

  /**
   * 데이터셋의 보안 등급을 지정 값으로 바꾼다(clone 상속). 자동 상향 표시는 건드리지 않는다. 호출자(DatasetSecurityService)는 모두 서비스 트랜잭션
   * 안이다.
   */
  void setDatasetLevel(long datasetId, Long levelId);

  /** 수동 등급 변경 — 등급을 바꾸면서 자동 상향 표시도 함께 지운다(남기면 하향 뒤에도 "자동 상향되었습니다" 배너가 남는다). */
  void setDatasetLevelClearingAutoRaise(long datasetId, long levelId);

  /**
   * 파이프라인 출력 등급 변경 — 등급을 바꾸고, 자동 상향이면 상향 시각(지금)도 남긴다.
   *
   * @param autoRaise true 면 자동 상향 시각을 현재 시각으로 기록
   */
  void setPipelineOutputLevel(long datasetId, long levelId, boolean autoRaise);

  /** 데이터셋의 자동 상향 시각. 상향된 적이 없으면(또는 데이터셋이 없으면) null. */
  LocalDateTime findAutoRaisedAt(long datasetId);

  /** 데이터셋의 자동 상향 표시를 지운다. */
  void clearAutoRaisedAt(long datasetId);

  /** 자동 상향 표시가 비어 있을 때만 이전 시각으로 되살린다(이번 실행 중에 더 최근 표시가 찍혔으면 그 값을 둔다). */
  void restoreAutoRaisedAtIfAbsent(long datasetId, LocalDateTime previousAutoRaisedAt);

  /**
   * 데이터셋 행을 {@code FOR UPDATE} 로 잠근다(호출 트랜잭션 끝까지 유지). 호출 서비스 메서드가 모두 트랜잭션이라 클래스 단위 REQUIRED 가 합류한다.
   *
   * @return 데이터셋 행이 있어 잠갔으면 true
   */
  boolean lockDatasetRow(long datasetId);

  /** 데이터셋 행을 {@code FOR UPDATE} 로 잠그면서 현재 등급 id 를 읽는다. 데이터셋이 없으면 비어 돌아온다. */
  Optional<Long> lockDatasetAndFindLevelId(long datasetId);

  /** 역할 id → 역할 열람 등급 id(max_security_level_id). 이 테넌트(RLS) 역할만 나온다. */
  Map<Long, Long> findRoleLevelIds(Collection<Long> roleIds);

  /** 이 테넌트(RLS)에 그 역할이 있는가. */
  boolean roleExists(long roleId);

  /** 허용 목록 후보 사용자 한 명. */
  record CandidateUser(Long id, String name, String email) {}

  /** 허용 목록 후보 역할 한 개(역할 열람 등급 포함). */
  record CandidateRole(Long id, String name, Long levelId) {}

  /** 이 테넌트 ACTIVE 멤버이면서 활성 계정인 사용자(이름 순). membership 은 RLS 없는 전역 테이블이라 tenant_id 를 명시한다. */
  List<CandidateUser> findCandidateUsers(long tenantId);

  /** 이 테넌트(RLS) 역할 전부(이름 순) — 허용 목록 후보용. */
  List<CandidateRole> findCandidateRoles();

  /** 사용자가 이 테넌트 ACTIVE 멤버이고 계정도 활성인가(허용 항목 추가 검증 — 후보 조회와 같은 기준). */
  boolean isActiveMember(long userId, long tenantId);

  /** 순서 일괄 갱신 — UNIQUE(tenant_id, rank) 를 트랜잭션 끝까지 미룬다(DEFERRABLE). */
  void updateRanks(Map<Long, Integer> newRanks);
}
