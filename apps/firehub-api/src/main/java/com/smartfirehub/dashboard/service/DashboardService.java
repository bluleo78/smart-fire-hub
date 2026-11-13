package com.smartfirehub.dashboard.service;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.ActivityFeedResponse.ActivityItem;
import com.smartfirehub.dashboard.dto.AttentionItemResponse;
import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.dto.RecentImportResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse.DatasetHealth;
import com.smartfirehub.dashboard.dto.SystemHealthResponse.PipelineHealth;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.AuditActivityRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.ExecutionRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.FailedImportRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.FailedPipelineRow;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.StoreTransactions;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 홈 대시보드(통계·건강도·주의 항목·활동) 조회.
 *
 * <p>보안 등급 가시성(스펙 §2.5 존재 은닉) — 홈 화면은 조회자가 볼 수 없는 데이터셋의 이름·건수·활동을 싣지 않는다. 각 공개 메서드는 두 형태다: 인자 없는
 * 형태는 요청 경로(홈 대시보드 API)용으로 현재 요청 사용자 자격을, {@link Clearance} 를 받는 형태는 비요청 경로(proactive 리포트 컨텍스트 수집 —
 * 요청 사용자가 없어 current() 가 "아무것도 못 봄"이 된다)용으로 명시 자격을 쓴다.
 *
 * <p>인자 없는 형태에도 @Transactional 을 둔다 — 자기 호출은 프록시를 우회하므로 위임받는 쪽 어노테이션만으로는 트랜잭션(=RLS GUC)이 열리지 않는다.
 * 시스템 상태({@code getSystemHealth})만은 어노테이션 대신 readOnly 템플릿으로 구간을 직접 연다(WD-80) — Tibero 테넌트의 빈 테이블
 * 판정을 PG 트랜잭션 밖으로 빼야 해서다. 템플릿은 자기 호출에서도 그대로 동작한다.
 *
 * <p>파이프라인 가시성(WD-272) — 요청 경로는 조회자에게 파이프라인 조회 권한({@code pipeline:read})이 없으면 파이프라인 이름·실행 이력·개수를 싣지
 * 않는다(개수 0, 목록 비움). 파이프라인 목록·실행 이력 API 가 403 인 사람에게 홈이 같은 정보를 보여 주지 않게 하려는 것이다. 비요청 경로({@link
 * Clearance} 형태 — 스마트 작업 리포트 컨텍스트)는 그 자격에 실린 권한 목록(작업 주인의 워크스페이스 권한)으로 같은 판정을 한다(WD-361). 주인 미상
 * 자격({@link Clearance#none})은 권한이 없어 파이프라인을 싣지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

  /** 홈 대시보드 조회 쿼리(WD-484) — 서비스의 트랜잭션 구간 안에서 불린다(리포지토리에 클래스 단위 트랜잭션 없음). */
  private final DashboardStatsRepository dashboardStatsRepository;

  /** 데이터셋 가시성 조건(보안 등급) — 클래스 Javadoc 참고. */
  private final DatasetAccessGuard datasetAccessGuard;

  private final ClearanceResolver clearanceResolver;

  /** 테넌트 저장소 해석(WD-80) — 빈 데이터셋 판정을 그 백엔드의 행 저장소로 한다. */
  private final DataStoreResolver dataStoreResolver;

  /**
   * 테넌트 저장소가 Tibero 인지 판정하는 단일 지점(WD-80) — Tibero 면 빈 테이블 판정을 PG 트랜잭션 밖에서 한다. 다른 서비스(차트·저장 쿼리 등)와
   * 같은 판정을 쓰려고 직접 비교하지 않고 이 빈에 맡긴다.
   */
  private final PipelineDataSchema pipelineDataSchema;

  /**
   * 시스템 상태 조회 구간의 readOnly 트랜잭션 템플릿(WD-80) — 예전 {@code @Transactional(readOnly = true)} 와 같은 값이다.
   * Tibero 질의를 트랜잭션 밖으로 빼려면 구간을 나눠야 해서 어노테이션 대신 쓴다. 공용 빈({@link
   * TransactionTemplateConfig#READ_ONLY})을 주입받는다.
   */
  @Qualifier(TransactionTemplateConfig.READ_ONLY)
  private final TransactionTemplate readOnlyTx;

  /** 홈에서 파이프라인 이름·실행 이력·개수를 볼 수 있는 권한(WD-272) — 파이프라인 목록·실행 이력 API 와 같은 코드다. */
  private static final String PIPELINE_READ = "pipeline:read";

  // 홈 대시보드 스파크라인에 표시할 추이 일수 (#669 — 하드코딩 배열 대체)
  private static final int TREND_DAYS = 7;

  /**
   * 날짜별 카운트 맵을 최근 TREND_DAYS일 범위의 정렬된 리스트로 변환한다 (과거→오늘 순).
   *
   * <p>데이터가 없는 날짜는 0으로 채운다 — 스파크라인이 실제 활동량 0을 그대로 반영하도록 하기 위함(#669).
   */
  private List<Integer> fillTrendDays(Map<LocalDate, Integer> countsByDay) {
    LocalDate today = LocalDate.now();
    List<Integer> trend = new ArrayList<>(TREND_DAYS);
    for (int i = TREND_DAYS - 1; i >= 0; i--) {
      trend.add(countsByDay.getOrDefault(today.minusDays(i), 0));
    }
    return trend;
  }

  /** 추이 집계 시작 시각 — 오늘 포함 최근 TREND_DAYS일의 첫날 0시. 파이프라인·데이터셋 추이가 같은 구간을 쓰게 한 곳에서 계산한다. */
  private static LocalDateTime trendStart() {
    return LocalDate.now().minusDays(TREND_DAYS - 1).atStartOfDay();
  }

  /**
   * 요청 사용자가 파이프라인 조회 권한을 가졌는지(WD-272). 인증 주체의 권한 목록은 {@code PermissionInterceptor} 가
   * {@code @RequirePermission} 을 검사할 때 보는 것과 같은 출처다 — 이 워크스페이스의 역할 권한이다.
   */
  private static boolean currentUserCanReadPipelines() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null
        && auth.getAuthorities().stream().anyMatch(a -> PIPELINE_READ.equals(a.getAuthority()));
  }

  /**
   * 명시 자격의 주인이 파이프라인 조회 권한을 가졌는지(WD-361). 자격의 권한 목록은 {@code ClearanceResolver} 가 인터셉터와 같은 출처
   * ({@code PermissionService.getUserPermissions})에서 채운다 — 요청 경로의 {@link
   * #currentUserCanReadPipelines()} 와 같은 기준이다.
   */
  private static boolean canReadPipelines(Clearance viewer) {
    return viewer.permissions().contains(PIPELINE_READ);
  }

  @Transactional(readOnly = true)
  public DashboardStatsResponse getStats() {
    return getStats(clearanceResolver.current(), currentUserCanReadPipelines());
  }

  @Transactional(readOnly = true)
  public DashboardStatsResponse getStats(Clearance viewer) {
    return getStats(viewer, canReadPipelines(viewer));
  }

  /**
   * @param includePipelines false 면 파이프라인 개수를 0, 최근 실행을 빈 목록으로 둔다 — 파이프라인 조회 권한이 없는 조회자(WD-272)
   */
  private DashboardStatsResponse getStats(Clearance viewer, boolean includePipelines) {
    // 조회자가 볼 수 있는 데이터셋만 센다·싣는다(보안 등급) — 숨김 데이터셋 개수·이름이 홈에서 새지 않게.
    DatasetVisibility visible = datasetAccessGuard.visibility(viewer);

    // 같은 dataset 표의 전체·원천·파생 수는 한 번에 센다(WD-733)
    DashboardStatsRepository.DatasetCounts datasetCounts =
        dashboardStatsRepository.countDatasetsByKind(visible);
    long totalDatasets = datasetCounts.total();
    long sourceDatasets = datasetCounts.source();
    long derivedDatasets = datasetCounts.derived();

    // 파이프라인 개수 — 조회 권한이 없으면 세지 않는다(WD-272). 전체·활성은 한 번에 센다(WD-733)
    long totalPipelines = 0L;
    long activePipelines = 0L;
    if (includePipelines) {
      DashboardStatsRepository.PipelineCounts pipelineCounts =
          dashboardStatsRepository.countPipelineStats();
      totalPipelines = pipelineCounts.total();
      activePipelines = pipelineCounts.active();
    }

    // 최근 임포트(상위 5개) — 감사 결과값을 화면 상태값으로 바꾼다.
    List<RecentImportResponse> recentImports =
        dashboardStatsRepository.findRecentImports(visible).stream()
            .map(
                r -> {
                  String status =
                      switch (r.result()) {
                        case "SUCCESS" -> "COMPLETED";
                        case "FAILURE" -> "FAILED";
                        default -> r.result();
                      };
                  return new RecentImportResponse(
                      r.id(), r.datasetName(), r.fileName(), status, r.actionTime());
                })
            .collect(Collectors.toList());

    // 최근 실행(상위 5개) — 파이프라인 이름이 실리므로 조회 권한이 없으면 비운다(WD-272)
    List<RecentExecutionResponse> recentExecutions = List.of();
    if (includePipelines) {
      recentExecutions = dashboardStatsRepository.findRecentExecutions();
    }

    return new DashboardStatsResponse(
        totalDatasets,
        sourceDatasets,
        derivedDatasets,
        totalPipelines,
        activePipelines,
        recentImports,
        recentExecutions);
  }

  /**
   * 요청 경로(홈 대시보드 API)의 시스템 상태 — 현재 요청 사용자 자격으로 계산한다.
   *
   * <p>예전 {@code @Transactional(readOnly = true)} 대신 readOnly 템플릿을 쓴다(WD-80). PG 는 그때처럼 자격 해석부터 전부
   * readOnly 트랜잭션 하나 안이다(안쪽 템플릿은 이 트랜잭션에 합류한다). Tibero 는 바깥 트랜잭션을 열어 두면 빈 테이블 판정(Tibero 질의)이 그 안에
   * 갇히므로, 자격 해석만 짧은 readOnly 트랜잭션에서 하고 나머지 구간은 {@link #getSystemHealth(Clearance)} 가 나눈다.
   */
  public SystemHealthResponse getSystemHealth() {
    // 권한은 요청 스레드의 인증 주체에서 읽는다 — 구간을 나누기 전에 한 번만(WD-272)
    boolean includePipelines = currentUserCanReadPipelines();
    return StoreTransactions.loadThenExecute(
        pipelineDataSchema,
        readOnlyTx,
        readOnlyTx,
        clearanceResolver::current,
        viewer -> getSystemHealth(viewer, includePipelines));
  }

  /**
   * 명시 자격으로 시스템 상태를 계산한다(비요청 경로 — proactive 리포트 컨텍스트 수집이 쓴다).
   *
   * <p>RLS 대상(파이프라인·데이터셋·감사 로그) 조회는 readOnly 트랜잭션 안에서 한다(WD-80, 예전 메서드 어노테이션과 같은 값). 빈 데이터셋 판정은 테넌트
   * 저장소로 하며, PG 는 같은 트랜잭션 안에서, Tibero 는 네트워크 너머 질의라 PG 커넥션을 쥐지 않도록 트랜잭션 밖에서 한다.
   */
  public SystemHealthResponse getSystemHealth(Clearance viewer) {
    return getSystemHealth(viewer, canReadPipelines(viewer));
  }

  /**
   * @param includePipelines false 면 파이프라인 건강도를 모두 0 으로 둔다 — 파이프라인 조회 권한이 없는 조회자(WD-272)
   */
  private SystemHealthResponse getSystemHealth(Clearance viewer, boolean includePipelines) {
    return StoreTransactions.loadThenExecute(
        pipelineDataSchema,
        readOnlyTx,
        readOnlyTx,
        () -> readHealth(viewer, includePipelines),
        read -> read.withEmpty(countEmptyDatasets(read.tableNames())));
  }

  /**
   * 빈 데이터셋 수 — 테넌트 저장소가 비어 있다고 판정한 테이블 수다. 판정하지 못한 테이블은 세지 않는다(실패를 "비어 있음"으로 보지 않는다). table_name 은
   * 전역 유니크라 테이블 수가 곧 데이터셋 수다(예전 조인이 데이터셋 행을 세던 것과 같다). 목록이 비어도 부른다 — 저장소가 연결 없이 끝낸다.
   */
  private int countEmptyDatasets(List<String> tableNames) {
    return dataStoreResolver.rowStore().emptyTables(tableNames).size();
  }

  /** 트랜잭션 안에서 읽은 시스템 상태 조각과, 빈 테이블 판정에 넘길 조회자 가시 데이터셋의 테이블 이름. 판정 결과를 받아 응답을 완성한다. */
  private record HealthRead(
      PipelineHealth pipelineHealth,
      int totalDatasets,
      int freshDatasets,
      int staleDatasets,
      List<Integer> datasetTrend,
      List<String> tableNames) {

    SystemHealthResponse withEmpty(int emptyDatasets) {
      return new SystemHealthResponse(
          pipelineHealth,
          new DatasetHealth(
              totalDatasets, freshDatasets, staleDatasets, emptyDatasets, datasetTrend));
    }
  }

  /** RLS 대상 테이블을 읽어 시스템 상태 조각을 모은다. readOnly 트랜잭션 안에서 불러야 한다. */
  private HealthRead readHealth(Clearance viewer, boolean includePipelines) {
    // ---- Pipeline health ---- 조회 권한이 없으면 세지 않고 0 으로 둔다(WD-272)
    PipelineHealth pipelineHealth =
        includePipelines
            ? readPipelineHealth()
            : new PipelineHealth(0, 0, 0, 0, 0, fillTrendDays(Map.of()));
    return readDatasetHealth(viewer, pipelineHealth);
  }

  /** 파이프라인 건강도(개수·최근 실행 상태·7일 추이)를 읽는다. readOnly 트랜잭션 안에서 불러야 한다. */
  private PipelineHealth readPipelineHealth() {
    int totalPipelines = Math.toIntExact(dashboardStatsRepository.countPipelines());
    int disabledPipelines = dashboardStatsRepository.countDisabledPipelines();

    // 활성 파이프라인별 마지막 실행 상태
    List<String> latestStatuses = dashboardStatsRepository.findLatestStatusesOfActivePipelines();

    int activePipelinesTotal = totalPipelines - disabledPipelines;

    int runningPipelines = (int) latestStatuses.stream().filter("RUNNING"::equals).count();
    int failingPipelines = (int) latestStatuses.stream().filter("FAILED"::equals).count();
    // healthy = completed + pending (waiting to run) + no execution yet
    int healthyPipelines = activePipelinesTotal - runningPipelines - failingPipelines;
    if (healthyPipelines < 0) healthyPipelines = 0;

    // ---- Pipeline trend (최근 7일 일자별 실행 건수, #669) ----
    LocalDateTime trendFrom = trendStart();
    Map<LocalDate, Integer> pipelineCountsByDay =
        dashboardStatsRepository.countExecutionsByDay(trendFrom);
    List<Integer> pipelineTrend = fillTrendDays(pipelineCountsByDay);

    return new PipelineHealth(
        totalPipelines,
        healthyPipelines,
        failingPipelines,
        runningPipelines,
        disabledPipelines,
        pipelineTrend);
  }

  /** 데이터셋 건강도를 읽어 파이프라인 건강도와 함께 조각으로 묶는다. readOnly 트랜잭션 안에서 불러야 한다. */
  private HealthRead readDatasetHealth(Clearance viewer, PipelineHealth pipelineHealth) {
    // ---- Dataset health ----
    // 데이터셋 건강도 개수는 조회자가 볼 수 있는 데이터셋만 집계한다(보안 등급 — 숨김 데이터셋 존재가 개수로 드러나지 않게).
    DatasetVisibility visible = datasetAccessGuard.visibility(viewer);
    int totalDatasets = Math.toIntExact(dashboardStatsRepository.countDatasets(visible));

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime freshThreshold = now.minusHours(24);

    // fresh: SOURCE datasets with a SUCCESS import within 24h
    int freshDatasets =
        dashboardStatsRepository.countFreshImportedDatasets(visible, freshThreshold);

    // Also count source datasets created within 24h with no imports (brand new = fresh)
    int newSourceNoImport =
        dashboardStatsRepository.countNewSourceDatasetsWithoutImport(visible, freshThreshold);
    freshDatasets += newSourceNoImport;

    // stale: source datasets whose last import was >24h ago (or has never been imported and >24h
    // old)
    LocalDateTime staleThreshold = now.minusHours(24);

    // Find SOURCE datasets with last import older than 24h
    int staleWithOldImport =
        dashboardStatsRepository.countStaleImportedDatasets(visible, staleThreshold);

    // Source datasets created >24h ago with no import at all
    int staleNoImport =
        dashboardStatsRepository.countStaleSourceDatasetsWithoutImport(visible, staleThreshold);
    int staleDatasets = staleWithOldImport + staleNoImport;

    // empty: 조회자가 볼 수 있는 표(TABLE) 데이터셋의 테이블 이름만 읽는다. 비어 있는지는 호출자가 테넌트 저장소로 판정한다(WD-80).
    List<String> tableNames = dashboardStatsRepository.findTableDatasetNames(visible);

    // ---- Dataset trend (최근 7일 일자별 임포트 이력 건수, #669) ----
    LocalDateTime trendFrom = trendStart();
    // 숨김 데이터셋 이력 제외·삭제된 데이터셋 이력 포함·IMPORT 만 집계 — 리포지토리 메서드 주석 참고.
    Map<LocalDate, Integer> datasetCountsByDay =
        dashboardStatsRepository.countImportsByDay(visible, trendFrom);
    List<Integer> datasetTrend = fillTrendDays(datasetCountsByDay);

    return new HealthRead(
        pipelineHealth, totalDatasets, freshDatasets, staleDatasets, datasetTrend, tableNames);
  }

  @Transactional(readOnly = true)
  public List<AttentionItemResponse> getAttentionItems() {
    return getAttentionItems(clearanceResolver.current(), currentUserCanReadPipelines());
  }

  @Transactional(readOnly = true)
  public List<AttentionItemResponse> getAttentionItems(Clearance viewer) {
    return getAttentionItems(viewer, canReadPipelines(viewer));
  }

  /**
   * @param includePipelines false 면 파이프라인 실패 항목을 싣지 않는다 — 파이프라인 조회 권한이 없는 조회자(WD-272)
   */
  private List<AttentionItemResponse> getAttentionItems(
      Clearance viewer, boolean includePipelines) {
    List<AttentionItemResponse> items = new ArrayList<>();
    // 임포트 실패 항목은 조회자가 볼 수 있는 데이터셋만(보안 등급 — 항목 제목에 데이터셋 이름이 실린다).
    DatasetVisibility visible = datasetAccessGuard.visibility(viewer);
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime twoHoursAgo = now.minusHours(2);
    LocalDateTime twentyFourHoursAgo = now.minusHours(24);

    // 1. Failed pipelines: active pipelines whose latest execution is FAILED
    // Get latest execution per pipeline
    // 조회 권한이 없으면 파이프라인 이름이 실린 항목을 싣지 않는다 — 질의도 하지 않는다(WD-272)
    List<FailedPipelineRow> failedPipelines = List.of();
    if (includePipelines) {
      failedPipelines = dashboardStatsRepository.findFailedActivePipelines();
    }

    for (FailedPipelineRow r : failedPipelines) {
      Long pipelineId = r.pipelineId();
      String pipelineName = r.pipelineName();
      LocalDateTime lastFailedAt = r.lastFailedAt();
      LocalDateTime lastSuccessAt = r.lastSuccessAt();

      // CRITICAL: failing for 2h+ (last success was >2h ago or never)
      boolean isCritical = lastSuccessAt == null || lastSuccessAt.isBefore(twoHoursAgo);
      String severity = isCritical ? "CRITICAL" : "WARNING";

      String description;
      if (lastSuccessAt != null) {
        description = "마지막 성공: " + formatTimeAgo(lastSuccessAt, now);
      } else {
        description = "성공 이력 없음";
      }

      items.add(
          new AttentionItemResponse(
              "PIPELINE_FAILED",
              severity,
              "파이프라인 '" + pipelineName + "' 실패",
              description,
              pipelineId,
              "PIPELINE",
              lastFailedAt != null ? lastFailedAt : now));
    }

    // 3. Failed imports within last 24h: WARNING
    List<FailedImportRow> failedImports =
        dashboardStatsRepository.findFailedImportsSince(visible, twentyFourHoursAgo);

    for (FailedImportRow r : failedImports) {
      Long datasetId = r.datasetId();
      String datasetName = r.datasetName();
      LocalDateTime occurredAt = r.occurredAt();

      items.add(
          new AttentionItemResponse(
              "IMPORT_FAILED",
              "WARNING",
              "데이터셋 '" + datasetName + "' 임포트 실패",
              "발생: " + formatTimeAgo(occurredAt, now),
              datasetId,
              "DATASET",
              occurredAt));
    }

    // Sort: CRITICAL first, then WARNING; within same severity, most recent first
    items.sort(
        Comparator.comparingInt((AttentionItemResponse a) -> severityOrder(a.severity()))
            .thenComparing(Comparator.comparing(AttentionItemResponse::occurredAt).reversed()));

    return items;
  }

  @Transactional(readOnly = true)
  public ActivityFeedResponse getActivityFeed(
      String typeFilter, String severityFilter, int page, int size) {
    return getActivityFeed(
        typeFilter,
        severityFilter,
        page,
        size,
        clearanceResolver.current(),
        currentUserCanReadPipelines());
  }

  @Transactional(readOnly = true)
  public ActivityFeedResponse getActivityFeed(
      String typeFilter, String severityFilter, int page, int size, Clearance viewer) {
    return getActivityFeed(
        typeFilter, severityFilter, page, size, viewer, canReadPipelines(viewer));
  }

  /**
   * @param includePipelines false 면 파이프라인 실행 이벤트를 싣지 않는다 — 파이프라인 조회 권한이 없는 조회자(WD-272)
   */
  private ActivityFeedResponse getActivityFeed(
      String typeFilter,
      String severityFilter,
      int page,
      int size,
      Clearance viewer,
      boolean includePipelines) {

    // Build activity items from two sources:
    // 1. pipeline_execution — pipeline events
    // 2. audit_log — import events + dataset creation events
    // Unresolved (failing) pipelines are pinned to top via isResolved=false + sort

    List<ActivityItem> allItems = new ArrayList<>();

    // ---- Pipeline executions ----
    // 조회 권한이 없으면 파이프라인 이름·실행 결과가 실린 이벤트를 싣지 않는다 — 질의도 하지 않는다(WD-272)
    List<ExecutionRow> executions = List.of();
    if (includePipelines) {
      executions = dashboardStatsRepository.findRecentExecutionsForFeed();
    }

    // To determine if a FAILED pipeline has since been resolved (later COMPLETED execution exists)
    // Build map: pipeline_id -> last execution status
    Map<Long, String> latestStatusByPipeline =
        executions.stream()
            .collect(
                Collectors.toMap(
                    ExecutionRow::pipelineId,
                    ExecutionRow::status,
                    (existing, replacement) -> existing // keep first (most recent)
                    ));

    for (ExecutionRow r : executions) {
      String status = r.status();
      String eventType =
          switch (status) {
            case "COMPLETED" -> "PIPELINE_COMPLETED";
            case "FAILED" -> "PIPELINE_FAILED";
            case "RUNNING" -> "PIPELINE_RUNNING";
            default -> "PIPELINE_" + status;
          };

      String severity =
          switch (status) {
            case "FAILED" -> "CRITICAL";
            case "RUNNING" -> "INFO";
            default -> "INFO";
          };

      Long pipelineId = r.pipelineId();
      String pipelineName = r.pipelineName();
      LocalDateTime occurredAt = r.createdAt();

      // isResolved: non-FAILED executions are always resolved;
      // FAILED is resolved only if the latest execution for that pipeline is COMPLETED
      boolean isResolved =
          !"FAILED".equals(status) || "COMPLETED".equals(latestStatusByPipeline.get(pipelineId));

      String description = buildPipelineDescription(status, r.startedAt(), r.completedAt());

      allItems.add(
          new ActivityItem(
              r.id(),
              eventType,
              "파이프라인 '" + pipelineName + "' " + statusLabel(status),
              description,
              severity,
              "PIPELINE",
              pipelineId,
              occurredAt,
              isResolved));
    }

    // ---- Audit log events (imports + dataset creation) ----
    // 분석 대시보드 생성(CREATE·dashboard) 행은 싣지 않는다(WD-464). 그 행은 작성자 본인 변경 감사로 남기 시작했는데,
    // 피드는 조회자 가시성을 보지 않아 비공개 대시보드를 만든 사실이 모든 사람 피드에 「대시보드 생성됨」으로 보이고
    // 이동할 화면도 없다. 예전 dashboard 분기는 그 행을 쓰는 곳이 없던 죽은 경로였다.
    // 보안 등급: 조회자가 볼 수 없는 데이터셋 유래 활동은 뺀다(조건 위치는 리포지토리 주석 참고).
    List<AuditActivityRow> auditItems =
        dashboardStatsRepository.findDatasetAuditActivities(datasetAccessGuard.visibility(viewer));

    for (AuditActivityRow r : auditItems) {
      String actionType = r.actionType();
      String resource = r.resource();
      String result = r.result();
      LocalDateTime occurredAt = r.actionTime();
      Long resourceId = r.resourceId() != null ? parseLongSafe(r.resourceId()) : null;
      String entityName = r.datasetName() != null ? r.datasetName() : resource + " #" + resourceId;

      String eventType;
      String severity;
      String title;
      String description;
      String entityType;

      if ("IMPORT".equals(actionType)) {
        boolean success = "SUCCESS".equals(result);
        eventType = success ? "IMPORT_COMPLETED" : "IMPORT_FAILED";
        severity = success ? "INFO" : "WARNING";
        title = "데이터셋 '" + entityName + "' 임포트 " + (success ? "완료" : "실패");
        description = r.description() != null ? r.description() : "";
        entityType = "DATASET";
      } else if ("CREATE".equals(actionType) && "dataset".equals(resource)) {
        eventType = "DATASET_CREATED";
        severity = "INFO";
        title = "데이터셋 '" + entityName + "' 생성됨";
        description = "";
        entityType = "DATASET";
      } else {
        continue;
      }

      allItems.add(
          new ActivityItem(
              r.id(),
              eventType,
              title,
              description,
              severity,
              entityType,
              resourceId,
              occurredAt,
              true // audit log entries are always considered resolved
              ));
    }

    // Apply filters
    List<ActivityItem> filtered =
        allItems.stream()
            .filter(
                item -> {
                  if (typeFilter != null
                      && !typeFilter.isBlank()
                      && !"ALL".equalsIgnoreCase(typeFilter)) {
                    String entityType = item.entityType();
                    boolean matches =
                        typeFilter.equalsIgnoreCase(entityType)
                            || (typeFilter.equalsIgnoreCase("PIPELINE")
                                && "PIPELINE".equals(entityType))
                            || (typeFilter.equalsIgnoreCase("DATASET")
                                && "DATASET".equals(entityType))
                            || (typeFilter.equalsIgnoreCase("IMPORT")
                                && item.eventType().startsWith("IMPORT"));
                    if (!matches) return false;
                  }
                  if (severityFilter != null
                      && !severityFilter.isBlank()
                      && !"ALL".equalsIgnoreCase(severityFilter)) {
                    if (!severityFilter.equalsIgnoreCase(item.severity())) return false;
                  }
                  return true;
                })
            .sorted(
                // Unresolved (isResolved=false) items first, then by occurredAt desc
                Comparator.comparingInt((ActivityItem i) -> i.isResolved() ? 1 : 0)
                    .thenComparing(Comparator.comparing(ActivityItem::occurredAt).reversed()))
            .collect(Collectors.toList());

    int totalCount = filtered.size();
    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, totalCount);

    List<ActivityItem> pageItems =
        fromIndex >= totalCount ? List.of() : filtered.subList(fromIndex, toIndex);

    return new ActivityFeedResponse(pageItems, totalCount, toIndex < totalCount);
  }

  // ---- Helpers ----

  private String formatTimeAgo(LocalDateTime time, LocalDateTime now) {
    long minutes = java.time.Duration.between(time, now).toMinutes();
    if (minutes < 60) return minutes + "분 전";
    long hours = minutes / 60;
    if (hours < 24) return hours + "시간 전";
    long days = hours / 24;
    return days + "일 전";
  }

  private int severityOrder(String severity) {
    return switch (severity) {
      case "CRITICAL" -> 0;
      case "WARNING" -> 1;
      default -> 2;
    };
  }

  private String statusLabel(String status) {
    return switch (status) {
      case "COMPLETED" -> "완료";
      case "FAILED" -> "실패";
      case "RUNNING" -> "실행중";
      case "PENDING" -> "대기";
      case "CANCELLED" -> "취소됨";
      default -> status;
    };
  }

  private String buildPipelineDescription(
      String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    if ("COMPLETED".equals(status) && startedAt != null && completedAt != null) {
      long seconds = java.time.Duration.between(startedAt, completedAt).getSeconds();
      if (seconds < 60) return "실행 시간: " + seconds + "초";
      return "실행 시간: " + (seconds / 60) + "분 " + (seconds % 60) + "초";
    }
    return "";
  }

  private Long parseLongSafe(String s) {
    if (s == null) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
