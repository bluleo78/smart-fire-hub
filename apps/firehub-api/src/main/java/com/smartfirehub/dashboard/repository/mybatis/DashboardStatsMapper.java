package com.smartfirehub.dashboard.repository.mybatis;

import com.smartfirehub.dashboard.dto.RecentExecutionResponse;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.AuditActivityRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.DatasetCounts;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.ExecutionRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.FailedImportRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.FailedPipelineRow;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.PipelineCounts;
import com.smartfirehub.dashboard.repository.DashboardStatsRepository.RecentImportRow;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 홈 대시보드 집계(WD-484) MyBatis 매퍼(WD-710). SQL 은 {@code mybatis/dashboard/DashboardStatsMapper.xml}.
 *
 * <p>가시성은 {@code vis}(데이터셋 가시성 조각 파라미터) 이름으로 받는다 — 조각이 {@code #{vis.x}} 로 읽는다. 시간 경계는 서비스가 계산한 값을
 * 그대로 바인딩한다(DB 시계를 쓰지 않는다).
 */
@PlatformMapper
public interface DashboardStatsMapper {

  /** 일자별 집계 한 칸 — 날짜는 두 DB 모두 날짜 값이라 {@link java.sql.Date} 로 읽는다(Tibero TRUNC 결과는 DATE). */
  record DayCount(java.sql.Date day, int cnt) {}

  long countDatasets(@Param("vis") VisibilityParams vis);

  /** 전체·원천·파생 데이터셋 수를 한 문장으로(WD-733). */
  DatasetCounts countDatasetsByKind(@Param("vis") VisibilityParams vis);

  long countPipelines();

  /** 전체·활성 파이프라인 수를 한 문장으로(WD-733). */
  PipelineCounts countPipelineStats();

  List<RecentImportRow> selectRecentImports(@Param("vis") VisibilityParams vis);

  List<RecentExecutionResponse> selectRecentExecutions();

  int countDisabledPipelines();

  List<String> selectLatestStatusesOfActivePipelines();

  List<DayCount> countExecutionsByDay(@Param("trendFrom") LocalDateTime trendFrom);

  int countFreshImportedDatasets(
      @Param("vis") VisibilityParams vis, @Param("threshold") LocalDateTime freshThreshold);

  int countNewSourceDatasetsWithoutImport(
      @Param("vis") VisibilityParams vis, @Param("threshold") LocalDateTime freshThreshold);

  int countStaleImportedDatasets(
      @Param("vis") VisibilityParams vis, @Param("threshold") LocalDateTime staleThreshold);

  int countStaleSourceDatasetsWithoutImport(
      @Param("vis") VisibilityParams vis, @Param("threshold") LocalDateTime staleThreshold);

  List<String> selectTableDatasetNames(@Param("vis") VisibilityParams vis);

  List<DayCount> countImportsByDay(
      @Param("vis") VisibilityParams vis, @Param("trendFrom") LocalDateTime trendFrom);

  List<FailedPipelineRow> selectFailedActivePipelines();

  List<FailedImportRow> selectFailedImportsSince(
      @Param("vis") VisibilityParams vis, @Param("since") LocalDateTime since);

  List<ExecutionRow> selectRecentExecutionsForFeed();

  List<AuditActivityRow> selectDatasetAuditActivities(@Param("vis") VisibilityParams vis);
}
