package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.StepExecutionResponse;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ActiveCountsRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ExecutionDetailRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.ExecutionInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StepExecutionInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StepStartRow;
import com.smartfirehub.pipeline.repository.mybatis.PipelineExecutionRows.StopStateRow;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * PipelineExecutionRepository 의 MyBatis 구현(WD-708) — {@code JooqPipelineExecutionRepository} 와 같은
 * 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 배경 잡(정리기·스윕 게이트·러너)은 앰비언트 트랜잭션이
 * 없고, 트랜잭션이 없으면 테넌트 컨텍스트가 심기지 않아 정책이 0행이 된다. REQUIRED 라 서비스 트랜잭션에는 합류한다.
 *
 * <p>Tibero 에는 RETURNING 이 없다. 생성 id 는 JDBC 생성 키로 받고, {@code requestCancel} 의 승자 판정(jOOQ 는
 * RETURNING 행 유무)은 조건부 UPDATE 의 영향 행 수로 하고 요청 시각은 같은 트랜잭션에서 다시 읽는다 — UPDATE 가 행 잠금을 쥐고 있어 다른 트랜잭션이
 * 그 사이에 바꿀 수 없다. 경쟁하는 두 트랜잭션 중 늦은 쪽은 행 잠금에서 기다렸다가 상태 조건을 다시 보고 0행이 된다(두 DB 공통, 계약 테스트가 실제로 경쟁시킨다).
 *
 * <p>시각은 jOOQ 와 같은 자바 값(하트비트·취소 시각은 자바 UTC)을 바인딩한다. 오류 메시지는 Tibero {@code VARCHAR2(65532)} 바이트 한도를
 * 넘지 않게 {@link TiberoTextLimits#truncateErrorMessage} 로 자른다(PG 에서도 같은 길이로 잘라 결과를 맞춘다). 스텝 로그는 CLOB
 * 이라 자르지 않는다.
 */
@Slf4j
@PlatformMyBatisRepository
@Transactional
public class MyBatisPipelineExecutionRepository implements PipelineExecutionRepository {

  /**
   * pipeline_execution.pipeline_id → pipeline(id) FK 이름 — PG V3 기본 이름, Tibero 기준선도 같은 이름으로 만들었다.
   */
  private static final String PIPELINE_FK_CONSTRAINT = "pipeline_execution_pipeline_id_fkey";

  private final PipelineExecutionMapper mapper;

  /** DB 오류 판정 창구 — FK 위반 제약 이름을 벤더 중립으로 알아본다(WD-486). */
  private final PlatformDbErrors platformDbErrors;

  public MyBatisPipelineExecutionRepository(
      PipelineExecutionMapper mapper, PlatformDbErrors platformDbErrors) {
    this.mapper = mapper;
    this.platformDbErrors = platformDbErrors;
  }

  @Override
  public Long createExecution(Long pipelineId, Long executedBy) {
    return createExecution(pipelineId, executedBy, "MANUAL", null);
  }

  @Override
  public Long createExecution(
      Long pipelineId, Long executedBy, String triggeredBy, Long triggerId) {
    // 제출 시점 하트비트(WD-230)는 jOOQ 와 같은 자바 UTC 시각이다 — 정리기 임계값도 자바 UTC 로 만든다.
    ExecutionInsert row =
        new ExecutionInsert(
            pipelineId,
            executedBy,
            LocalDateTime.now(ZoneOffset.UTC),
            triggeredBy != null ? triggeredBy : "MANUAL",
            triggerId);
    try {
      mapper.insertExecution(row);
    } catch (DataIntegrityViolationException e) {
      // 파이프라인이 그 사이 삭제됐으면(WD-437) FK 위반 대신 404 로 알린다. 다른 FK 위반(실행자 등)은 그대로 던진다.
      if (platformDbErrors
          .violatedConstraint(e)
          .filter(PIPELINE_FK_CONSTRAINT::equals)
          .isPresent()) {
        throw new PipelineNotFoundException();
      }
      throw e;
    }
    return row.getId();
  }

  @Override
  public boolean updateExecutionStatus(
      Long executionId, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    return updateExecutionStatus(executionId, status, startedAt, completedAt, null);
  }

  @Override
  public boolean updateExecutionStatus(
      Long executionId,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      String errorMessage) {
    // 끝나지 않은 실행만 갱신한다(WD-153) — 조건부 UPDATE 라 먼저 마감한 쪽이 이긴다.
    return mapper.updateExecutionStatus(
            executionId,
            status,
            startedAt,
            completedAt,
            TiberoTextLimits.truncateErrorMessage(errorMessage))
        == 1;
  }

  @Override
  public boolean finishUnlessCancelRequested(
      Long executionId, String status, LocalDateTime completedAt) {
    return mapper.finishUnlessCancelRequested(executionId, status, completedAt) == 1;
  }

  @Override
  public int touchHeartbeat(Collection<Long> executionIds, LocalDateTime now) {
    if (executionIds == null || executionIds.isEmpty()) {
      return 0;
    }
    // Tibero IN 목록 상한(1000)을 넘지 않게 조각으로 나눈다.
    return mapper.touchHeartbeat(InChunks.of(executionIds), now);
  }

  @Override
  public List<StaleExecution> findStaleExecutions(long tenantId, LocalDateTime threshold) {
    return mapper.selectStale(tenantId, threshold).stream()
        .map(r -> new StaleExecution(r.executionId(), r.pipelineId()))
        .toList();
  }

  @Override
  public boolean failInterruptedAndCloseSteps(long executionId, LocalDateTime now, String message) {
    String truncated = TiberoTextLimits.truncateErrorMessage(message);
    if (mapper.failExecution(executionId, now, truncated) != 1) {
      // 진 호출은 단계도 건드리지 않는다.
      return false;
    }
    // RUNNING 단계는 FAILED, 아직 시작 못 한 PENDING 은 SKIPPED — 실행이 이미 FAILED 라 실행 상태 조건 없이 쓴다. 한 트랜잭션이다.
    mapper.failRunningSteps(executionId, now, truncated);
    mapper.skipPendingSteps(executionId, truncated, now);
    return true;
  }

  @Override
  public boolean cancelAndSkipPendingSteps(Long executionId, LocalDateTime completedAt) {
    boolean won =
        updateExecutionStatus(
            executionId,
            "CANCELLED",
            null,
            completedAt,
            PipelineExecutionCancelledException.CANCELLED_MESSAGE);
    if (won) {
      skipPendingStepsForCancel(executionId);
    }
    return won;
  }

  @Override
  public boolean isStopRequested(long executionId) {
    StopStateRow row = mapper.selectStopState(executionId);
    if (row == null) {
      log.warn("Pipeline execution {} 실행 행이 없어 중지합니다(삭제되었거나 보이지 않음 — 사용자 취소 아님)", executionId);
      return true;
    }
    return row.cancelRequestedAt() != null
        || !("PENDING".equals(row.status()) || "RUNNING".equals(row.status()));
  }

  @Override
  public ActiveExecutionState findActiveExecutionState(long pipelineId) {
    ActiveCountsRow counts = mapper.selectActiveCounts(pipelineId);
    if (counts.active() == 0) {
      return ActiveExecutionState.NONE;
    }
    return counts.notCancelling() > 0
        ? ActiveExecutionState.ACTIVE
        : ActiveExecutionState.CANCELLING;
  }

  @Override
  public Optional<CancelRequestResult> requestCancel(Long executionId, Long userId) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    // 1) PENDING 이면 그 자리에서 마감 — 영향 행 1 이 곧 이 호출의 승리다(jOOQ 의 RETURNING 행 유무).
    if (mapper.cancelPending(
            executionId, now, userId, PipelineExecutionCancelledException.CANCELLED_MESSAGE)
        == 1) {
      skipPendingStepsForCancel(executionId);
      return Optional.of(
          new CancelRequestResult("CANCELLED", mapper.selectCancelRequestedAt(executionId)));
    }
    // 2) RUNNING 이면 요청만 남긴다(첫 요청 유지). 돌려줄 시각은 저장된 값(첫 요청 시각)을 다시 읽는다.
    if (mapper.requestCancelRunning(executionId, now, userId) == 1) {
      return Optional.of(
          new CancelRequestResult("RUNNING", mapper.selectCancelRequestedAt(executionId)));
    }
    // 3) 이미 끝났거나 없는(보이지 않는) 실행.
    return Optional.empty();
  }

  @Override
  public void skipPendingStepsForCancel(Long executionId) {
    mapper.skipPendingSteps(
        executionId,
        PipelineExecutionCancelledException.SKIPPED_BY_CANCEL_MESSAGE,
        LocalDateTime.now(ZoneOffset.UTC));
  }

  @Override
  public boolean hasRunningExecution(long tenantId) {
    return mapper.selectRunningMarker(tenantId) != null;
  }

  @Override
  public Optional<Long> findPipelineIdOfExecution(Long executionId) {
    return Optional.ofNullable(mapper.selectPipelineId(executionId));
  }

  @Override
  public Long createStepExecution(Long executionId, Long stepId, String stepName) {
    StepExecutionInsert row = new StepExecutionInsert(executionId, stepId, stepName);
    mapper.insertStepExecution(row);
    return row.getId();
  }

  @Override
  public boolean hasOverlappingStepExecution(long stepExecId) {
    StepStartRow me = mapper.selectStepStart(stepExecId);
    // 판정할 수 없으면(행 없음·스텝 id 없음·시작 전) 겹친 것으로 본다 — 넓히지 않는 쪽이 안전.
    if (me == null || me.stepId() == null || me.startedAt() == null) {
      return true;
    }
    return mapper.selectOverlappingMarker(stepExecId, me.stepId(), me.startedAt()) != null;
  }

  @Override
  public void updateStepProgress(Long stepExecId, Integer outputRows, String log) {
    mapper.updateStepProgress(stepExecId, outputRows, log);
  }

  @Override
  public void setInjectedLastRunAt(Long stepExecId, OffsetDateTime value) {
    // 같은 순간을 Instant 로 바인딩한다 — PG timestamptz 는 기본 핸들러, Tibero UTC 벽시계 TIMESTAMP 는
    // UtcInstantTypeHandler.
    mapper.setInjectedLastRunAt(stepExecId, value == null ? null : value.toInstant());
  }

  @Override
  public boolean updateStepExecution(
      Long stepExecId,
      String status,
      Integer outputRows,
      String logText,
      String errorMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt) {
    int rows =
        mapper.updateStepExecution(
            stepExecId,
            status,
            outputRows,
            logText,
            TiberoTextLimits.truncateErrorMessage(errorMessage),
            startedAt,
            completedAt);
    if (rows == 0) {
      log.debug("스텝 실행 {} 쓰기 무반영 — 부모 실행이 이미 끝났거나 행이 없다", stepExecId);
    }
    return rows == 1;
  }

  @Override
  public List<PipelineExecutionResponse> findExecutionsByPipelineId(
      Long pipelineId, int page, int size) {
    // WD-532: 한 페이지만 읽는다(최신 id 순). offset 은 jOOQ 판과 같은 long 곱.
    return mapper.selectExecutionPage(pipelineId, size, (long) page * size).stream()
        .map(
            r ->
                new PipelineExecutionResponse(
                    r.id(),
                    r.pipelineId(),
                    r.status(),
                    PipelineRows.displayName(r.userName(), r.executedBy()),
                    r.startedAt(),
                    r.completedAt(),
                    r.createdAt(),
                    r.triggeredBy(),
                    r.triggerName(),
                    r.cancelRequestedAt()))
        .toList();
  }

  @Override
  public long countExecutionsByPipelineId(Long pipelineId) {
    return mapper.countByPipeline(pipelineId);
  }

  @Override
  public Optional<ExecutionDetailResponse> findExecutionById(Long executionId) {
    ExecutionDetailRow exec = mapper.selectExecutionDetail(executionId);
    if (exec == null) {
      return Optional.empty();
    }
    List<StepExecutionResponse> stepExecutions = findStepExecutionsByExecutionId(executionId);
    return Optional.of(
        new ExecutionDetailResponse(
            exec.id(),
            exec.pipelineId(),
            exec.pipelineName(),
            exec.status(),
            PipelineRows.displayName(exec.userName(), exec.executedBy()),
            stepExecutions,
            exec.startedAt(),
            exec.completedAt(),
            exec.createdAt(),
            exec.errorMessage(),
            false,
            exec.cancelRequestedAt(),
            exec.cancellerName()));
  }

  @Override
  public Optional<Long> findExecutedById(Long executionId) {
    return Optional.ofNullable(mapper.selectExecutedBy(executionId));
  }

  @Override
  public List<StepExecutionResponse> findStepExecutionsByExecutionId(Long executionId) {
    return mapper.selectStepExecutions(executionId).stream()
        .map(
            r ->
                new StepExecutionResponse(
                    r.id(),
                    r.stepId(),
                    r.stepName(),
                    r.status(),
                    r.outputRows(),
                    r.log(),
                    r.errorMessage(),
                    r.startedAt(),
                    r.completedAt(),
                    false))
        .toList();
  }

  @Override
  public int deleteCompletedCreatedBefore(LocalDateTime cutoff) {
    return mapper.deleteCompletedCreatedBefore(cutoff);
  }
}
