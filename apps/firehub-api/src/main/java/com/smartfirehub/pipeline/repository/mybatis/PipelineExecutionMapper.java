package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ActiveCountsRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ExecutionDetailRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ExecutionInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ExecutionListRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StaleRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StepExecutionInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StepExecutionRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StepStartRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StopStateRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 파이프라인 실행(pipeline_execution)·스텝 실행(pipeline_step_execution) MyBatis 매퍼(WD-708). SQL 은 {@code
 * mybatis/pipeline/PipelineExecutionMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡고(인자로 테넌트를 받는 두
 * 조회만 명시 조건), 상태 조건부 쓰기는 영향 행 수를 돌려준다(Tibero 에 RETURNING 이 없어 승자 판정을 행 수로 한다).
 */
@PlatformMapper
public interface PipelineExecutionMapper {

  /** 실행 한 건을 PENDING 으로 넣고 생성 키를 홀더에 돌려준다. */
  int insertExecution(ExecutionInsert row);

  /** 미완료 실행의 상태를 쓴다 — null 인자는 건드리지 않는다. RUNNING 전환이면 시작 시각을 하트비트로도 쓴다. */
  int updateExecutionStatus(
      @Param("executionId") Long executionId,
      @Param("status") String status,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("completedAt") LocalDateTime completedAt,
      @Param("errorMessage") String errorMessage);

  int finishUnlessCancelRequested(
      @Param("executionId") Long executionId,
      @Param("status") String status,
      @Param("completedAt") LocalDateTime completedAt);

  int touchHeartbeat(@Param("chunks") List<List<Long>> chunks, @Param("now") LocalDateTime now);

  List<StaleRow> selectStale(
      @Param("tenantId") long tenantId, @Param("threshold") LocalDateTime threshold);

  /** 미완료 실행을 FAILED 로 닫는다(진 호출이면 0행). */
  int failExecution(
      @Param("executionId") long executionId,
      @Param("now") LocalDateTime now,
      @Param("message") String message);

  /** 실행의 RUNNING 스텝을 FAILED 로 닫는다(실행 상태 조건 없음). */
  int failRunningSteps(
      @Param("executionId") long executionId,
      @Param("now") LocalDateTime now,
      @Param("message") String message);

  /** 실행의 PENDING 스텝을 사유·시각과 함께 SKIPPED 로 닫는다(실행 상태 조건 없음). */
  int skipPendingSteps(
      @Param("executionId") Long executionId,
      @Param("message") String message,
      @Param("now") LocalDateTime now);

  StopStateRow selectStopState(@Param("executionId") long executionId);

  ActiveCountsRow selectActiveCounts(@Param("pipelineId") long pipelineId);

  /** PENDING 실행을 그 자리에서 CANCELLED 로 마감한다 — 1 이면 이 호출이 이겼다. */
  int cancelPending(
      @Param("executionId") Long executionId,
      @Param("now") LocalDateTime now,
      @Param("userId") Long userId,
      @Param("message") String message);

  /** RUNNING 실행에 취소 요청을 남긴다(첫 요청 시각·요청자를 덮지 않는다) — 1 이면 RUNNING 이었다. */
  int requestCancelRunning(
      @Param("executionId") Long executionId,
      @Param("now") LocalDateTime now,
      @Param("userId") Long userId);

  LocalDateTime selectCancelRequestedAt(@Param("executionId") Long executionId);

  /** 그 테넌트에 RUNNING 실행이 있으면 1, 없으면 null. */
  Integer selectRunningMarker(@Param("tenantId") long tenantId);

  Long selectPipelineId(@Param("executionId") Long executionId);

  Long selectExecutedBy(@Param("executionId") Long executionId);

  /** 스텝 실행 한 건을 PENDING 으로 넣고 생성 키를 홀더에 돌려준다. */
  int insertStepExecution(StepExecutionInsert row);

  StepStartRow selectStepStart(@Param("stepExecId") long stepExecId);

  /** 같은 스텝의 다른 실행 중 겹친 것이 있으면 1, 없으면 null. */
  Integer selectOverlappingMarker(
      @Param("stepExecId") long stepExecId,
      @Param("stepId") Long stepId,
      @Param("startedAt") LocalDateTime startedAt);

  int updateStepProgress(
      @Param("stepExecId") Long stepExecId,
      @Param("outputRows") Integer outputRows,
      @Param("log") String log);

  int setInjectedLastRunAt(@Param("stepExecId") Long stepExecId, @Param("value") Instant value);

  /** 부모 실행이 미완료일 때만 스텝 실행을 쓴다 — null 인자(상태 제외)는 건드리지 않는다. */
  int updateStepExecution(
      @Param("stepExecId") Long stepExecId,
      @Param("status") String status,
      @Param("outputRows") Integer outputRows,
      @Param("log") String log,
      @Param("errorMessage") String errorMessage,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("completedAt") LocalDateTime completedAt);

  List<ExecutionListRow> selectExecutionPage(
      @Param("pipelineId") Long pipelineId,
      @Param("limit") int limit,
      @Param("offset") long offset);

  long countByPipeline(@Param("pipelineId") Long pipelineId);

  ExecutionDetailRow selectExecutionDetail(@Param("executionId") Long executionId);

  List<StepExecutionRow> selectStepExecutions(@Param("executionId") Long executionId);

  int deleteCompletedCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
