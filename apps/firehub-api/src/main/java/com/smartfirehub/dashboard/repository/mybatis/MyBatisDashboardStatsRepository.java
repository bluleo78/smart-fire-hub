package com.smartfirehub.dashboard.repository.mybatis;

import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashboardStatsRepository 의 MyBatis 구현(WD-710) — {@code JooqDashboardStatsRepository} 와 같은 의미.
 *
 * <p>읽기 전용 집계 20개다. 가시성은 {@link MyBatisDatasetVisibility#params} 로 펼쳐 공유 조각({@code
 * securitylevel/DatasetVisibility.xml} 의 {@code visible})에 넘긴다. 벤더 차이(날짜 버킷·metadata 필드·resource_id
 * 숫자 판정· {@code "RESOURCE"} 열·불리언·LIMIT)는 XML 조각이 맡는다.
 *
 * <p>클래스 단위 트랜잭션을 두지 않는다 — jOOQ 구현과 같이 서비스의 readOnly 트랜잭션 구간 안에서 불려 그 트랜잭션(=RLS 테넌트)을 쓴다. 여기서 새
 * 트랜잭션을 열면 Tibero 테넌트의 빈 테이블 판정을 트랜잭션 밖으로 뺀 구간 나눔(WD-80)이 흐트러진다.
 */
@PlatformMyBatisRepository
public class MyBatisDashboardStatsRepository implements DashboardStatsRepository {

  private final DashboardStatsMapper mapper;

  public MyBatisDashboardStatsRepository(DashboardStatsMapper mapper) {
    this.mapper = mapper;
  }

  /** 가시성 값을 조각 파라미터로 펼친다. */
  private static VisibilityParams vis(DatasetVisibility visibility) {
    return MyBatisDatasetVisibility.params(visibility);
  }

  /** 일자별 집계 행을 날짜 → 건수 맵으로 바꾼다(jOOQ fetchMap 과 같은 모양, 순서 없음). */
  private static Map<LocalDate, Integer> toDayMap(List<DashboardStatsMapper.DayCount> rows) {
    Map<LocalDate, Integer> map = new HashMap<>();
    for (DashboardStatsMapper.DayCount r : rows) {
      map.put(r.day().toLocalDate(), r.cnt());
    }
    return map;
  }

  @Override
  public long countDatasets(DatasetVisibility visibility) {
    return mapper.countDatasets(vis(visibility));
  }

  @Override
  public DatasetCounts countDatasetsByKind(DatasetVisibility visibility) {
    return mapper.countDatasetsByKind(vis(visibility));
  }

  @Override
  public long countPipelines() {
    return mapper.countPipelines();
  }

  @Override
  public PipelineCounts countPipelineStats() {
    return mapper.countPipelineStats();
  }

  @Override
  public List<RecentImportRow> findRecentImports(DatasetVisibility visibility) {
    return mapper.selectRecentImports(vis(visibility));
  }

  @Override
  public List<RecentExecutionResponse> findRecentExecutions() {
    return mapper.selectRecentExecutions();
  }

  @Override
  public int countDisabledPipelines() {
    return mapper.countDisabledPipelines();
  }

  @Override
  public List<String> findLatestStatusesOfActivePipelines() {
    return mapper.selectLatestStatusesOfActivePipelines();
  }

  @Override
  public Map<LocalDate, Integer> countExecutionsByDay(LocalDateTime trendFrom) {
    return toDayMap(mapper.countExecutionsByDay(trendFrom));
  }

  @Override
  public int countFreshImportedDatasets(
      DatasetVisibility visibility, LocalDateTime freshThreshold) {
    return mapper.countFreshImportedDatasets(vis(visibility), freshThreshold);
  }

  @Override
  public int countNewSourceDatasetsWithoutImport(
      DatasetVisibility visibility, LocalDateTime freshThreshold) {
    return mapper.countNewSourceDatasetsWithoutImport(vis(visibility), freshThreshold);
  }

  @Override
  public int countStaleImportedDatasets(
      DatasetVisibility visibility, LocalDateTime staleThreshold) {
    return mapper.countStaleImportedDatasets(vis(visibility), staleThreshold);
  }

  @Override
  public int countStaleSourceDatasetsWithoutImport(
      DatasetVisibility visibility, LocalDateTime staleThreshold) {
    return mapper.countStaleSourceDatasetsWithoutImport(vis(visibility), staleThreshold);
  }

  @Override
  public List<String> findTableDatasetNames(DatasetVisibility visibility) {
    return mapper.selectTableDatasetNames(vis(visibility));
  }

  @Override
  public Map<LocalDate, Integer> countImportsByDay(
      DatasetVisibility visibility, LocalDateTime trendFrom) {
    return toDayMap(mapper.countImportsByDay(vis(visibility), trendFrom));
  }

  @Override
  public List<FailedPipelineRow> findFailedActivePipelines() {
    return mapper.selectFailedActivePipelines();
  }

  @Override
  public List<FailedImportRow> findFailedImportsSince(
      DatasetVisibility visibility, LocalDateTime since) {
    return mapper.selectFailedImportsSince(vis(visibility), since);
  }

  @Override
  public List<ExecutionRow> findRecentExecutionsForFeed() {
    return mapper.selectRecentExecutionsForFeed();
  }

  @Override
  public List<AuditActivityRow> findDatasetAuditActivities(DatasetVisibility visibility) {
    return mapper.selectDatasetAuditActivities(vis(visibility));
  }
}
