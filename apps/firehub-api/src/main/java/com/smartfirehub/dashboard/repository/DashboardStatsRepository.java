package com.smartfirehub.dashboard.repository;

import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 홈 대시보드(통계·건강도·주의 항목·활동) 조회 쿼리 모음(WD-484).
 *
 * <p>{@code DashboardService} 의 직접 jOOQ 쿼리를 문장 그대로 옮긴 것이다. 행·집계 값만 돌려주고, 응답 DTO 조립·추이 채우기·정렬·포맷은
 * 서비스에 남는다.
 *
 * <p>클래스 단위 {@code @Transactional} 을 두지 않는다 — 서비스의 기존 트랜잭션 구간(readOnly 어노테이션·readOnly 템플릿) 안에서 불려 그
 * 트랜잭션(=RLS GUC)을 그대로 쓴다. 여기서 새 트랜잭션을 열면 Tibero 테넌트의 빈 테이블 판정을 PG 트랜잭션 밖으로 뺀 구간 나눔(WD-80)이 흐트러진다.
 *
 * <p>가시성(보안 등급)은 서비스가 {@code DatasetAccessGuard} 로 {@code DatasetVisibility} 를 만들어 넘긴다. 시간 경계(24시간
 * 전·추이 시작 시각)도 서비스가 예전과 같은 시점에 계산해 넘긴다 — 한 화면의 여러 집계가 같은 기준 시각을 쓰게 하려는 것이다.
 *
 * <p>구현은 {@code JooqDashboardStatsRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DashboardStatsRepository {

  /** 최근 임포트 한 건 — 감사 결과값(SUCCESS/FAILURE)을 그대로 든다. 화면 상태값으로 바꾸는 것은 서비스가 한다. */
  record RecentImportRow(
      Long id, String datasetName, String fileName, String result, LocalDateTime actionTime) {}

  /** 마지막 실행이 실패인 활성 파이프라인 한 건 — 마지막 실패·성공 시각으로 서비스가 심각도·설명을 정한다. */
  record FailedPipelineRow(
      Long pipelineId,
      String pipelineName,
      LocalDateTime lastFailedAt,
      LocalDateTime lastSuccessAt) {}

  /** 최근 24시간 임포트 실패 한 건. */
  record FailedImportRow(Long datasetId, String datasetName, LocalDateTime occurredAt) {}

  /** 활동 피드용 파이프라인 실행 한 건. */
  record ExecutionRow(
      Long id,
      Long pipelineId,
      String pipelineName,
      String status,
      LocalDateTime createdAt,
      LocalDateTime completedAt,
      LocalDateTime startedAt) {}

  /** 활동 피드용 감사 로그 한 건 — 데이터셋 행이 없으면(삭제된 데이터셋) datasetName·datasetId 가 null 이다. */
  record AuditActivityRow(
      Long id,
      String actionType,
      String resource,
      String resourceId,
      String result,
      LocalDateTime actionTime,
      String description,
      String datasetName,
      Long datasetId) {}

  /** 조회자가 볼 수 있는 데이터셋 수(통계 카드·건강도 공용). */
  long countDatasets(DatasetVisibility visibility);

  /** 조회자가 볼 수 있는 원천(TABLE·SOURCE) 데이터셋 수. */
  long countSourceDatasets(DatasetVisibility visibility);

  /** 조회자가 볼 수 있는 파생(TABLE·DERIVED) 데이터셋 수. */
  long countDerivedDatasets(DatasetVisibility visibility);

  /** 전체 파이프라인 수(통계 카드·건강도 공용). */
  long countPipelines();

  /** 활성 파이프라인 수(통계 카드용). */
  long countActivePipelines();

  /** 최근 임포트 상위 5건(audit_log) — 조회자가 볼 수 있는 데이터셋만. */
  List<RecentImportRow> findRecentImports(DatasetVisibility visibility);

  /** 최근 파이프라인 실행 상위 5건. */
  List<RecentExecutionResponse> findRecentExecutions();

  /** 비활성 파이프라인 수(is_active = false). */
  int countDisabledPipelines();

  /** 활성 파이프라인별 마지막 실행 상태 목록(실행 이력이 없는 파이프라인은 빠진다). */
  List<String> findLatestStatusesOfActivePipelines();

  /** 일자별 파이프라인 실행 건수(#669) — {@code trendFrom} 이후 생성된 실행만. */
  Map<LocalDate, Integer> countExecutionsByDay(LocalDateTime trendFrom);

  /** fresh: {@code freshThreshold} 이후 SUCCESS 임포트가 있는 원천 데이터셋 수. */
  int countFreshImportedDatasets(DatasetVisibility visibility, LocalDateTime freshThreshold);

  /** {@code freshThreshold} 이후 생성됐고 임포트 이력이 없는 원천 데이터셋 수(새로 만든 것 = fresh). */
  int countNewSourceDatasetsWithoutImport(
      DatasetVisibility visibility, LocalDateTime freshThreshold);

  /** stale: SUCCESS 임포트는 있으나 {@code staleThreshold} 이후에는 없는 원천 데이터셋 수. */
  int countStaleImportedDatasets(DatasetVisibility visibility, LocalDateTime staleThreshold);

  /** {@code staleThreshold} 이전에 생성됐고 임포트 이력이 전혀 없는 원천 데이터셋 수. */
  int countStaleSourceDatasetsWithoutImport(
      DatasetVisibility visibility, LocalDateTime staleThreshold);

  /**
   * 조회자가 볼 수 있는 표(TABLE) 데이터셋의 테이블 이름. 비어 있는지는 호출자가 테넌트 저장소로 판정한다(WD-80).
   *
   * <p>DOCUMENT/FILE 은 물리 테이블이 없어 판정 대상이 아니다 — 예전 pg_stat_user_tables 조인도 이들을 (테이블이 없어) 뺐으므로 PG 결과는
   * 같고, Tibero 는 없는 테이블에 대한 원격 질의를 하지 않는다.
   */
  List<String> findTableDatasetNames(DatasetVisibility visibility);

  /**
   * 일자별 데이터셋 임포트 이력 건수(#669) — {@code trendFrom} 이후.
   *
   * <p>숨김 데이터셋의 이력은 추이에서 뺀다(볼 수 있는 것만 집계). 데이터셋 행이 없는 이력(삭제된 데이터셋)은 기존처럼 센다 — 삭제된 데이터셋은 이름·존재를 드러낼
   * 대상이 없다. 임포트(IMPORT)만 센다 — 데이터셋 정보·필드·행 편집도 resource=dataset 으로 감사에 남으므로(WD-463) 모두 세면 행 편집 한
   * 번마다 추이가 올라간다.
   */
  Map<LocalDate, Integer> countImportsByDay(DatasetVisibility visibility, LocalDateTime trendFrom);

  /** 마지막 실행이 FAILED 인 활성 파이프라인과 그 마지막 실패·성공 시각. */
  List<FailedPipelineRow> findFailedActivePipelines();

  /** {@code since} 이후 임포트 실패 — 조회자가 볼 수 있는 데이터셋만, 최신순. */
  List<FailedImportRow> findFailedImportsSince(DatasetVisibility visibility, LocalDateTime since);

  /** 피드용 최근 파이프라인 실행(최대 500건, 생성 시각 최신순 — 서비스의 "파이프라인별 마지막 상태" 판정이 이 순서에 기댄다). */
  List<ExecutionRow> findRecentExecutionsForFeed();

  /**
   * 피드용 데이터셋 감사 이벤트(임포트·생성, 최대 500건, 최신순).
   *
   * <p>분석 대시보드 생성(CREATE·dashboard) 행은 싣지 않는다(WD-464) — 서비스 주석 참고.
   */
  List<AuditActivityRow> findDatasetAuditActivities(DatasetVisibility visibility);
}
