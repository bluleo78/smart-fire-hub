package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.LoadStrategy;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * PipelineExecutionService의 비동기 파이프라인 실행을 담당하는 별도 Spring Bean.
 *
 * <p>같은 클래스 내 자기호출(self-invocation)로는 Spring AOP 프록시를 우회하여 {@code @Async}가 적용되지 않는 문제를 방지하기 위해 별도
 * 빈으로 분리한다. PipelineExecutionService가 이 빈을 주입받아 호출함으로써 프록시를 통한 정상적인 비동기 실행이 보장된다.
 *
 * <p>참고: DataExportAsyncRunner(이슈 #167)와 동일한 패턴으로 수정됨(이슈 #189).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PipelineAsyncRunner {

  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineRepository pipelineRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final SqlColumnProbe sqlColumnProbe;
  private final SqlScriptExecutor sqlExecutor;
  private final PythonScriptExecutor pythonExecutor;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final ApiCallExecutor apiCallExecutor;
  private final ApiConnectionService apiConnectionService;
  private final ObjectMapper objectMapper;
  private final PermissionChecker permissionChecker;
  private final ExecutorClient executorClient;
  private final AiClassifyExecutor aiClassifyExecutor;
  private final TempDatasetService tempDatasetService;
  private final SqlValidator sqlValidator;
  private final PythonScriptValidator pythonScriptValidator;
  private final IncrementalCursorService incrementalCursorService;


  /**
   * 파이프라인을 비동기로 실행한다.
   *
   * <p>이 메서드는 {@code pipelineExecutor} 스레드풀에서 실행되므로 HTTP 요청 스레드를 블록하지 않는다. DAG 위상 정렬 후 스텝을 순서대로
   * 실행하고 완료 이벤트를 발행한다.
   *
   * @param pipelineId 실행할 파이프라인 ID
   * @param executionId 생성된 파이프라인 실행 레코드 ID
   * @param steps 파이프라인 스텝 목록
   * @param stepDependencyMap 스텝 ID → 의존 스텝 ID 목록 매핑
   * @param stepIdToStepExecId 스텝 ID → 스텝 실행 레코드 ID 매핑
   * <p><b>트랜잭션 경계(P2-b Task 5)</b>: 이 메서드는 의도적으로 {@code @Transactional} 이 아니다.
   * 파이프라인 실행은 수 분이 걸릴 수 있어 전체를 한 트랜잭션으로 감싸면 커넥션을 그만큼 점유한다.
   * 상태 갱신({@code updateExecutionStatus}/{@code updateStepExecution})은 모두 <b>단일 행 쓰기</b>이고
   * 여러 건이 함께 커밋돼야 하는 불변식이 없으므로(스텝 상태는 각각 독립, 최종 상태는 스텝 종료 후
   * 한 번), 리포지토리의 클래스 레벨 {@code @Transactional} 이 여는 짧은 트랜잭션으로 충분하다 —
   * 그 트랜잭션이 곧 RLS GUC 공급 지점이다.
   *
   * @param userId 실행 요청 사용자 ID (Python/AI 권한 체크에 사용)
   * @param executorEnabled 외부 실행기 활성화 여부
   */
  @Async("pipelineExecutor")
  public void executeAsync(
      Long pipelineId,
      Long executionId,
      List<PipelineStepResponse> steps,
      Map<Long, List<Long>> stepDependencyMap,
      Map<Long, Long> stepIdToStepExecId,
      Long userId,
      boolean executorEnabled) {

    LocalDateTime executionStartedAt = LocalDateTime.now(ZoneOffset.UTC);
    Long pipelineCreatedBy = pipelineRepository.findCreatedByIdById(pipelineId).orElse(null);
    String pipelineName = pipelineRepository.findNameById(pipelineId).orElse("Pipeline");

    try {
      // 실행 상태를 RUNNING으로 업데이트
      executionRepository.updateExecutionStatus(executionId, "RUNNING", executionStartedAt, null);

      // 위상 정렬로 실행 순서 결정
      List<PipelineStepResponse> executionOrder = topologicalSort(steps, stepDependencyMap);

      // 스텝별 실행 상태 추적
      Map<Long, String> stepStatuses = new HashMap<>();

      // 순서대로 스텝 실행
      for (PipelineStepResponse step : executionOrder) {
        Long stepExecId = stepIdToStepExecId.get(step.id());

        // 의존 스텝이 모두 COMPLETED인지 확인
        boolean canExecute = true;
        List<Long> dependencies = stepDependencyMap.get(step.id());

        for (Long depStepId : dependencies) {
          String depStatus = stepStatuses.get(depStepId);
          if (!"COMPLETED".equals(depStatus)) {
            canExecute = false;
            break;
          }
        }

        if (!canExecute) {
          // 의존 스텝 실패/스킵으로 이 스텝도 SKIPPED 처리
          executionRepository.updateStepExecution(
              stepExecId,
              "SKIPPED",
              null,
              null,
              "Dependency failed or skipped",
              null,
              LocalDateTime.now(ZoneOffset.UTC));
          stepStatuses.put(step.id(), "SKIPPED");
          log.info("Step {} skipped due to failed dependency", step.name());
        } else {
          // 스텝 실행
          String status =
              executeStep(stepExecId, step, pipelineId, pipelineName, userId, executorEnabled);
          stepStatuses.put(step.id(), status);
        }
      }

      // 전체 실행 최종 상태 결정
      boolean allCompleted = stepStatuses.values().stream().allMatch(s -> "COMPLETED".equals(s));
      boolean anyFailed = stepStatuses.values().stream().anyMatch(s -> "FAILED".equals(s));

      String finalStatus;
      if (allCompleted) {
        finalStatus = "COMPLETED";
      } else if (anyFailed) {
        finalStatus = "FAILED";
      } else {
        finalStatus = "COMPLETED"; // 일부 스킵됐지만 실패 없음
      }

      executionRepository.updateExecutionStatus(
          executionId, finalStatus, null, LocalDateTime.now(ZoneOffset.UTC));
      log.info("Pipeline execution {} completed with status: {}", executionId, finalStatus);

      // 체인 트리거를 위한 완료 이벤트 발행.
      // 이 스레드(pipelineExecutor)는 TenantContextTaskDecorator 로 테넌트를 승계받은 상태이고,
      // @Async 리스너 제출도 이 스레드에서 일어나므로 테넌트가 그대로 이어진다 — 그래서 이벤트
      // 페이로드에 tenantId 를 따로 싣지 않는다. (P2-b Task 5)
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, finalStatus, pipelineCreatedBy));

    } catch (Exception e) {
      log.error("Pipeline execution {} failed with exception", executionId, e);
      // 스텝 실행 레코드가 하나도 생성되기 전에 던져진 예외(토폴로지 정렬 실패, DB 오류 등)는
      // 스텝 레벨 error_message로 남길 곳이 없어 원인이 완전히 유실됐다(#517).
      // 최상위 예외 메시지를 execution 레벨 error_message로 보존한다.
      executionRepository.updateExecutionStatus(
          executionId, "FAILED", null, LocalDateTime.now(ZoneOffset.UTC), e.getMessage());

      // 실패 이벤트 발행
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, "FAILED", pipelineCreatedBy));
    }
  }

  /**
   * Kahn's algorithm 기반 위상 정렬로 스텝 실행 순서를 결정한다.
   *
   * @param steps 전체 스텝 목록
   * @param stepDependencyMap 스텝 ID → 의존 스텝 ID 목록
   * @return 실행 순서가 보장된 스텝 목록
   */
  List<PipelineStepResponse> topologicalSort(
      List<PipelineStepResponse> steps, Map<Long, List<Long>> stepDependencyMap) {
    // 역방향 의존성 맵 (자식 → 부모) 및 진입 차수 맵 구성
    Map<Long, List<Long>> reverseDeps = new HashMap<>();
    Map<Long, Integer> inDegree = new HashMap<>();

    for (PipelineStepResponse step : steps) {
      reverseDeps.put(step.id(), new ArrayList<>());
      inDegree.put(step.id(), 0);
    }

    for (PipelineStepResponse step : steps) {
      List<Long> deps = stepDependencyMap.get(step.id());
      inDegree.put(step.id(), deps.size());

      for (Long depStepId : deps) {
        reverseDeps.get(depStepId).add(step.id());
      }
    }

    // Kahn's algorithm: 진입 차수 0인 노드부터 처리
    Queue<Long> queue = new LinkedList<>();
    for (PipelineStepResponse step : steps) {
      if (inDegree.get(step.id()) == 0) {
        queue.offer(step.id());
      }
    }

    List<Long> sortedStepIds = new ArrayList<>();

    while (!queue.isEmpty()) {
      Long currentStepId = queue.poll();
      sortedStepIds.add(currentStepId);

      for (Long childStepId : reverseDeps.get(currentStepId)) {
        inDegree.put(childStepId, inDegree.get(childStepId) - 1);
        if (inDegree.get(childStepId) == 0) {
          queue.offer(childStepId);
        }
      }
    }

    // ID → 스텝 응답 매핑 후 정렬 결과 반환
    Map<Long, PipelineStepResponse> stepMap = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      stepMap.put(step.id(), step);
    }

    List<PipelineStepResponse> result = new ArrayList<>();
    for (Long stepId : sortedStepIds) {
      result.add(stepMap.get(stepId));
    }

    return result;
  }

  /**
   * 개별 파이프라인 스텝을 실행한다. 스크립트 타입(SQL/PYTHON/API_CALL/AI_CLASSIFY)에 따라 적절한 실행기를 호출하고 결과를 기록한다.
   *
   * @param stepExecId 스텝 실행 레코드 ID
   * @param step 실행할 스텝 정보
   * @param pipelineId 파이프라인 ID
   * @param pipelineName 파이프라인 이름 (로그/임시 데이터셋 생성에 사용)
   * @param userId 실행 사용자 ID
   * @param executorEnabled 외부 Python/API 실행기 활성화 여부
   * @return 실행 결과 상태 ("COMPLETED" 또는 "FAILED")
   */
  String executeStep(
      Long stepExecId,
      PipelineStepResponse step,
      Long pipelineId,
      String pipelineName,
      Long userId,
      boolean executorEnabled) {
    LocalDateTime stepStartedAt = LocalDateTime.now(ZoneOffset.UTC);

    try {
      // 스텝 상태를 RUNNING으로 업데이트
      executionRepository.updateStepExecution(
          stepExecId, "RUNNING", null, null, null, stepStartedAt, null);

      // 출력 데이터셋 ID 및 테이블명 결정 (임시 데이터셋 포함)
      Long outputDatasetId = step.outputDatasetId();

      String outputTableName = null;
      if (outputDatasetId != null) {
        outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElse(null);
      }

      // 전략 문자열 원본(미지정이면 기본 REPLACE). 아래 API_CALL 실행기 호출은 enum 이 아니라 이 원본을
      // 그대로 넘긴다 — strategy.name() 으로 바꾸면 소문자 레거시 값("append")이 대문자로 둔갑해 실행기가
      // 보는 값이 달라진다.
      String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";

      // 로드 전략을 이 메서드에서 딱 한 번 해석한다. load_strategy 컬럼에는 서버 쪽 enum·체크 제약이
      // 없어(PipelineStepRepository 는 null 만 "REPLACE" 로 매핑) 임의 문자열이 그대로 들어올 수 있으므로
      // 해석 실패는 REPLACE 로 폴백하고 경고를 한 번만 남긴다 — 예전에는 같은 폴백이 비SQL 분기와 SQL
      // 분기에 따로 하나씩 있었다. 대소문자 무시(LoadStrategy.parse)라 소문자 레거시 행("append")도
      // APPEND 로 해석된다.
      LoadStrategy strategy =
          LoadStrategy.parse(loadStrategy)
              .orElseGet(
                  () -> {
                    // 미지정(null)은 위에서 이미 "REPLACE" 로 바뀌어 해석에 성공하므로, 여기 오는 값은
                    // 오직 해석할 수 없는 값(오타·과거 값)뿐이다.
                    log.warn("Unknown load strategy '{}', falling back to REPLACE", loadStrategy);
                    return LoadStrategy.REPLACE;
                  });

      // Fix round 1, nit 5 — MERGE 는 SQL 스텝 전용 거부를 아래 로드 전략 블록(비SQL 타입만 타는 블록)에
      // 두면 API_CALL/AI_CLASSIFY/실행기 켠 PYTHON 은 애초에 그 블록 진입 조건에서 제외돼 있어
      // (바로 아래 if) 거기 도달하지 못한다 — 즉 레거시 PYTHON+MERGE 행이 실행기 켠 상태로 오면
      // 아무 거부도 없이 그냥 APPEND 처럼(비우지 않고) 조용히 실행된다. 저장 시점(PipelineService.
      // saveSteps)이 이미 이 조합을 거부하지만, 그 우회(레거시 데이터 등)에 대한 2차 방어는 스텝
      // 타입을 가리지 않고 걸어야 의미가 있으므로 모든 타입 분기보다 앞에 둔다.
      if (strategy == LoadStrategy.MERGE && !"SQL".equals(step.scriptType())) {
        throw new ScriptExecutionException("MERGE 는 SQL 스텝 전용입니다");
      }

      // 여기서 로드 전략을 직접 처리하는 유일한 경우는 <b>실행기를 끈 PYTHON 스텝</b>뿐이다.
      // API_CALL·AI_CLASSIFY·실행기 켠 PYTHON 은 각 실행기가 임시 테이블 맞바꿈으로 직접 처리하고,
      // SQL 스텝은 REPLACE 비우기를 즉시 truncate 할지 INSERT 와 같은 트랜잭션으로 보낼 DELETE 선행
      // 문장으로 만들지를 SQL 분기(isSelect 판단 이후)에서 결정한다(Task 4, 원자성). 스크립트 타입은
      // 이 네 가지가 전부다(DB CHECK 제약 pipeline_step_script_type_check, V35).
      if ("PYTHON".equals(step.scriptType()) && !executorEnabled) {
        // 위에서 이미 MERGE+비SQL 조합을 걸렀으므로 여기 도달하는 전략은 REPLACE/APPEND 뿐이다
        // (알 수 없는 값은 이미 REPLACE 로 폴백됐다).
        if (strategy == LoadStrategy.APPEND) {
          log.info("APPEND strategy: Skipping truncation for output table: {}", outputTableName);
        } else if (outputTableName != null) {
          log.info("REPLACE strategy: Truncating output table: {}", outputTableName);
          dataTableRowService.truncateTable(outputTableName);
        }
      }

      // 증분 처리 상태 — SQL 분기 안에서 정해지지만, 책갈피 전진은 실행 성공 이후(분기 밖)에 하므로
      // 메서드 스코프에 둔다.
      boolean incrementalStep = false;
      OffsetDateTime stepCursorCandidate = null;
      boolean stepWasFullRebuild = false;

      // 스크립트 타입별 실행
      String executionLog;
      if ("SQL".equals(step.scriptType())) {
        // 출력 비우기를 INSERT 와 같은 트랜잭션으로 보내기 위한 선행 문장 — 따로 커밋하면 실패 시 출력이
        // 빈 채로 남는다(Task 4). SELECT 자동 적재(REPLACE)일 때만 채워진다 — 아래에서 결정한다.
        List<String> preStatements = new ArrayList<>();
        String sql = step.scriptContent().trim();
        sql = resolveStepReferences(sql, pipelineId, step);

        // ── 증분 처리({{last_run_at}}) ────────────────────────────────────────────────
        // 책갈피 후보값은 SQL 실행 "전에" 잡아야 한다 — 실행 도중 커밋되는 트랜잭션의 행
        // (_updated_at = 그 트랜잭션 시작 시각)을 다음 실행이 놓치지 않기 위해서다. 실행 후에 잡으면
        // 그 구간이 영원히 비어 버린다. 값 자체의 안전한 계산(시각 먼저 → 활성 트랜잭션 최소값)은
        // V123 의 DB 함수가 책임진다.
        incrementalStep = LastRunAtPlaceholder.isUsedIn(sql);
        if (incrementalStep) {
          // 저장 시점(PipelineService.saveSteps)이 이미 REPLACE+증분을 거부하지만, 그 검증을 우회해
          // 저장된 레거시 행에 대한 2차 방어를 둔다 — REPLACE 로 돌면 매 실행 출력에 변경분만 남는다.
          if (strategy == LoadStrategy.REPLACE) {
            throw new ScriptExecutionException(
                "{{last_run_at}} 은 REPLACE 와 함께 쓸 수 없습니다(매 실행 출력이 변경분만 남습니다). MERGE 를 사용하세요.");
          }
          StepCursor cursor =
              stepRepository
                  .findCursor(step.id())
                  .orElseThrow(
                      () -> new ScriptExecutionException("증분 스텝의 책갈피를 찾을 수 없습니다: " + step.name()));
          stepWasFullRebuild = cursor.fullRebuildPending();
          // 전체 재생성 예약이면 책갈피를 무시하고 전체를 읽는다(-infinity).
          OffsetDateTime injected = stepWasFullRebuild ? null : cursor.lastRunAt();
          stepCursorCandidate = incrementalCursorService.captureCandidate();
          sql = LastRunAtPlaceholder.substitute(sql, injected);
          // 소스 테이블의 백필(_updated_at 컬럼+트리거) 완료 여부를 여기서 따로 확인하지 않는다.
          // V124 백필은 락을 못 잡은 테이블을 건너뛸 수 있지만, 그 결과는 fail-closed 다:
          // 컬럼이 없으면 사용자 SQL 의 `_updated_at >= {{last_run_at}}` 이 PG 에서
          // "column _updated_at does not exist" 로 즉시 실패한다. 그리고 "컬럼은 있는데 트리거가
          // 없는"(=증분이 조용히 틀리는) 조합은 V124·수리 스크립트가 컬럼과 트리거를 한 (서브)
          // 트랜잭션에 넣기 때문에 만들어지지 않는다. 즉 여기 가드를 두어도 막을 새로운 사고가 없다.
          // (초안에서 실제로 가드를 넣었다가 걷어냈다 — 이 경로의 SqlValidator 는 미한정 테이블
          //  참조를 거부하므로 미한정 이름 집합이 항상 비어 가드가 무조건 통과하는 죽은 코드였다.
          //  "보호한다고 주장하는 죽은 코드"는 보호가 없는 것보다 나쁘다.)
          executionRepository.setInjectedLastRunAt(stepExecId, injected);
        }

        // 실행 직전 재검증 — 저장 이후 정책 변경/우회 방지. probe/wrappedSql 결합은 이 검증 통과 후이므로
        // 단일 statement·세미콜론 없음이 보장되어 구조적으로 안전하다. (#136)
        sqlValidator.validate(sql);
        boolean isSelect = isSelectStatement(sql);
        // 이번 실행에서 SQL 스텝 출력을 위해 임시 데이터셋을 자동 생성/재사용했는지 여부.
        // 예약어 컬럼명 별칭 처리(renameReservedColumn*)는 이 경로에서만 적용해야 한다 — 사용자가
        // 명시적으로 지정한 기존 데이터셋(outputDatasetId가 원래부터 있던 경우)은 실제로 "id"라는
        // 정상 사용자 컬럼을 가질 수 있으므로 그 이름을 그대로 매칭해야 한다(#645).
        boolean tempDatasetAutoCreated = false;
        // probe 결과를 스텝당 한 번만 얻어 재사용한다 — 아래 두 블록이 같은 sql 을 각각 probe 하면
        // 테넌트 풀 대여·트랜잭션·왕복이 두 벌 나가고, 그 사이 풀이 축출·close() 될 틈까지 생긴다.
        // SELECT 가 아니면 probe 자체가 필요 없으므로 지연 획득한다.
        List<ColumnInfo> probedColumns = null;

        // SELECT이고 outputDatasetId가 없으면 임시 데이터셋 자동 생성
        if (isSelect && outputDatasetId == null) {
          tempDatasetAutoCreated = true;
          Long stepId = step.id();
          // SELECT * FROM {{#N}} 처럼 이전 스텝(또는 실제 데이터셋)의 결과를 그대로 재사용하면
          // 결과 컬럼에 시스템 예약 컬럼(id/import_id/created_at/_updated_at)이 그대로 섞여 들어온다.
          // V124 백필 이후 _updated_at 은 모든 데이터셋 테이블에 있으므로 SELECT * 는 항상 이 경로를 탄다.
          // 이 컬럼들을 그대로 새 임시 데이터셋의 사용자 컬럼으로 넘기면
          // DataTableService의 예약어 가드(사용자가 신규 데이터셋에 직접 그 이름을 짓는 것을 막기 위한 것)에
          // 걸려 실행이 항상 실패한다(#645). 자동 생성 경로에서만 예약어 컬럼명을 자동으로
          // 별칭 처리(rename-on-conflict)해 우회한다 — 사용자가 명시적으로 짓는 다른 스텝 타입의
          // 출력 컬럼명 검증은 그대로 유지한다.
          probedColumns = sqlColumnProbe.columnsWithTypes(sql);
          List<ColumnInfo> selectColumns = renameReservedColumns(probedColumns);

          Optional<Long> existingDatasetId = tempDatasetService.findExistingTempDataset(stepId);
          if (existingDatasetId.isPresent()) {
            Long dsId = existingDatasetId.get();
            if (tempDatasetService.hasSchemaChanged(dsId, selectColumns)) {
              log.info("Schema changed for step {}, recreating temp dataset", step.name());
              tempDatasetService.deleteTempDataset(dsId);
              outputDatasetId =
                  tempDatasetService.createTempDataset(
                      selectColumns, pipelineId, pipelineName, stepId, step.name(), userId);
            } else {
              log.info("Reusing existing temp dataset {} for step {}", dsId, step.name());
              outputDatasetId = dsId;
            }
          } else {
            log.info("Creating new temp dataset for step {}", step.name());
            outputDatasetId =
                tempDatasetService.createTempDataset(
                    selectColumns, pipelineId, pipelineName, stepId, step.name(), userId);
          }
          outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          // 여기서 즉시 truncate 하지 않는다 — 임시 데이터셋도 이번 실행 재사용 시 이전 실행 결과를
          // 담고 있어(findExistingTempDataset) 일반 출력 테이블과 같은 원자성 문제를 겪는다. 비우기는
          // 아래 REPLACE 판단 블록에서 DELETE 선행 문장으로 통일해 처리한다(Task 4).
        }

        // 출력 비우기 결정 — SELECT(자동 적재)는 DELETE 를 INSERT 와 같은 트랜잭션으로 보낼 선행 문장으로
        // 쌓고, 비SELECT(사용자가 직접 쓴 INSERT/UPDATE/DELETE)는 기존처럼 별도 트랜잭션으로 즉시
        // truncate 한다 — 사용자 DML 자체가 이미 트랜잭션 원자성을 스스로 책임지는 영역이라 기존 동작을
        // 바꾸지 않는다.
        //
        // 알 수 없는 loadStrategy 는 이미 위(메서드 상단)에서 경고와 함께 REPLACE 로 해석됐으므로, 여기는
        // REPLACE/APPEND/MERGE 세 가지만 본다 — 알 수 없는 값이 조용히 아무것도 비우지 않고 매 실행마다
        // 행이 누적되던 회귀(Fix round 1, 리뷰 지적 1)는 그 해석에서 이미 막힌다.
        //
        // MERGE 는 APPEND 처럼 출력을 비우지 않는다 — MERGE 의 존재 이유 자체가 "기존 행은 그대로 두고
        // PK 로 upsert"이므로, 여기서 비우면 그 전 실행 결과가 통째로 사라진 뒤 이번 실행분만 남는다
        // (REPLACE 와 다를 게 없어진다). preStatements 도 비워 둔다 — 아래 SELECT 래핑 분기가 MERGE 일 때
        // 자체적으로 사용하지 않는다.
        boolean isMerge = strategy == LoadStrategy.MERGE;
        if (strategy == LoadStrategy.REPLACE) {
          if (outputTableName != null) {
            if (isSelect) {
              preStatements.add(OutputClearStatement.deleteAll(outputTableName));
            } else {
              log.info("REPLACE strategy: Truncating output table: {}", outputTableName);
              dataTableRowService.truncateTable(outputTableName);
            }
          }
        }

        // (stepWasFullRebuild 는 증분 스텝일 때만 참이 될 수 있으므로 증분 여부를 따로 묻지 않는다.)
        // 전체 재생성 예약이 걸린 증분 스텝은 이번 실행에서 출력을 통째로 비운다 — 증분 스텝은 APPEND/
        // MERGE 라 평소에는 출력을 비우지 않으므로, 예약을 해소하려면 여기서 DELETE 선행 문장을 넣어야
        // 한다. 본 INSERT 와 같은 트랜잭션이므로 실패하면 출력이 그대로 보존된다(Task 4 와 같은 계약).
        // 위 REPLACE 블록과 중복될 일은 없다 — 증분 스텝은 바로 위에서 APPEND/MERGE 가 아니면 예외를
        // 던지므로, 여기 도달하는 증분 스텝의 preStatements 는 언제나 빈 목록이다.
        //
        // <b>isSelect 가 반드시 필요하다.</b> SELECT 자동 적재 경로만이 이 DELETE 뒤에 출력을 다시
        // 채운다(INSERT INTO ... SELECT 래핑). 사용자가 직접 쓴 INSERT/UPDATE/DELETE 스텝
        // (APPEND + {{last_run_at}} 조합은 저장 시점에 거부되지 않는다)에 이 DELETE 를 얹으면, 출력을
        // 비운 뒤 사용자 DML 이 그 자리를 채운다는 보장이 전혀 없어 출력이 빈 채로 COMPLETED 가 되고
        // 책갈피까지 전진한다 — 조용한 전량 손실이다. 비SELECT 증분 스텝의 "전체 재생성"은 출력을
        // 비우는 것이 아니라 <b>전체 읽기</b>(-infinity 치환)까지만을 뜻한다. 무엇을 다시 쓸지는
        // 사용자 DML 이 스스로 정한다.
        if (stepWasFullRebuild && isSelect && outputTableName != null) {
          preStatements.add(OutputClearStatement.deleteAll(outputTableName));
        }

        if (isSelect && outputTableName != null && outputDatasetId != null) {
          // SELECT 자동 적재: 컬럼 추출 → 매칭 → INSERT INTO ... SELECT 래핑
          // 이번 실행에서 임시 데이터셋을 자동 생성/재사용한 경우에만 위와 동일한 규칙으로 예약어
          // 컬럼명을 별칭 처리한다 — 임시 데이터셋에 실제로 저장된 컬럼명(id_1 등)과 정확히
          // 매칭되어야 하기 때문이다. 사용자가 직접 지정한 기존 출력 데이터셋에는 이 별칭 처리를
          // 적용하지 않는다 — 그 데이터셋의 "id" 컬럼은 예약어 충돌이 아니라 사용자가 실제로
          // 만든 정상 컬럼일 수 있다(#645).
          // 위 자동 생성 블록이 이미 probe 했으면 그 결과를 그대로 쓴다(이름은 ColumnInfo 에서 뽑는다).
          if (probedColumns == null) {
            probedColumns = sqlColumnProbe.columnsWithTypes(sql);
          }
          List<String> rawSelectColumns = probedColumns.stream().map(ColumnInfo::name).toList();
          List<String> selectColumns =
              tempDatasetAutoCreated ? renameReservedColumnNames(rawSelectColumns) : rawSelectColumns;

          // 출력 데이터셋의 컬럼은 **하나도 빼지 않는다**. 특히 is_primary_key 컬럼을 제외하면 안 된다
          // (#684). 이 경로는 대상 목록만 좁히고 SELECT 식 목록은 그대로 두기 때문에, 대상에서 한
          // 컬럼이라도 빠지면 식 개수가 어긋나 PostgreSQL 이 INSERT has more expressions than target
          // columns 로 거부한다 — 2026-09-17 운영 장애가 정확히 이것이다.
          //
          // 예전에는 여기서 !col.isPrimaryKey() 로 걸렀다. "PK 는 시스템이 채우는 대리키"라는 가정이
          // 었는데 이 스키마에서는 성립하지 않는다:
          //  - 물리 PK 는 DataTableService 가 id BIGSERIAL PRIMARY KEY 로 **따로** 만든다.
          //  - id/import_id/created_at 은 시스템 예약어라 dataset_column 에 애초에 들어올 수 없다.
          //  - is_primary_key 가 붙은 컬럼은 평범한 NOT NULL 컬럼 + UNIQUE 인덱스(ux_<table>_pk)일
          //    뿐이고 기본값도 없다 — 즉 대리키가 아니라 **사용자가 값을 넣어야 하는 업무 키**다.
          // 그래서 빼면 개수 불일치로 실패하고, SELECT 에서도 빼면 이번엔 NOT NULL 위반이 난다.
          // 어느 쪽으로도 성공할 수 없었다.
          // 출력 데이터셋 컬럼 메타데이터는 여기서 한 번만 읽는다 — 아래 MERGE 분기의 PK 목록도 같은
          // 목록에서 뽑는다(같은 스텝 실행 안에서 두 번 읽을 이유가 없다).
          List<DatasetColumnResponse> outputColumns =
              columnRepository.findByDatasetId(outputDatasetId);
          java.util.Set<String> outputColumnNames =
              outputColumns.stream()
                  .map(DatasetColumnResponse::columnName)
                  .collect(Collectors.toSet());

          List<String> matchedColumns =
              selectColumns.stream().filter(outputColumnNames::contains).toList();

          if (matchedColumns.isEmpty()) {
            throw new ScriptExecutionException(
                "SELECT 결과 컬럼이 출력 데이터셋의 컬럼과 일치하지 않습니다. "
                    + "SELECT alias를 출력 테이블 컬럼명과 맞춰주세요. "
                    + "SELECT 컬럼: "
                    + selectColumns
                    + ", 출력 테이블 컬럼: "
                    + outputColumnNames);
          }

          // 출력 테이블은 현재 테넌트의 데이터 스키마에 있다 — 스키마명을 직접 적지 않고
          // DataSchema.qualify 로 조립한다(테이블명 인용·따옴표 이중화까지 그쪽이 책임진다).
          String wrappedSql;
          List<String> mergePkColumns = List.of();
          if (isMerge) {
            // MERGE 는 출력 데이터셋의 기존 PK(ux_<table>_pk 유니크 인덱스, dataset_column.is_primary_key
            // 로 기록됨)를 그대로 재사용한다 — 사용자가 별도로 병합 키를 고르지 않는다. 저장 시점
            // (PipelineService.saveSteps)에 PK 존재가 이미 검증됐지만, 저장 이후 데이터셋 스키마가
            // 바뀌었을 수 있어(컬럼 삭제 등) 실행 시점에도 다시 확인한다 — 조용히 APPEND 로 격하되면
            // 안 되므로 여기서 명확히 실패시킨다.
            mergePkColumns =
                outputColumns.stream()
                    .filter(DatasetColumnResponse::isPrimaryKey)
                    .map(DatasetColumnResponse::columnName)
                    .toList();
            if (mergePkColumns.isEmpty()) {
              throw new ScriptExecutionException(
                  "MERGE 에는 출력 데이터셋 PK 컬럼이 필요합니다(데이터셋 스키마가 변경되었는지 확인하세요).");
            }
            wrappedSql =
                MergeSqlBuilder.build(
                    DataSchema.qualify(outputTableName), matchedColumns, mergePkColumns, sql);
          } else {
            String columnList =
                matchedColumns.stream()
                    .map(col -> "\"" + col + "\"")
                    .collect(Collectors.joining(", "));
            wrappedSql =
                "INSERT INTO "
                    + DataSchema.qualify(outputTableName)
                    + " ("
                    + columnList
                    + ") "
                    + sql;
          }

          if (executorEnabled) {
            var result = executorClient.executeSql(wrappedSql, preStatements);
            if (!result.success()) {
              String translated = translateMergeError(isMerge, result.error(), mergePkColumns);
              if (translated != null) {
                throw new ScriptExecutionException(translated);
              }
              throw new ScriptExecutionException("SQL 실행 실패: " + result.error());
            }
            executionLog = result.executionLog();
          } else {
            try {
              executionLog = sqlExecutor.execute(preStatements, wrappedSql);
            } catch (ScriptExecutionException e) {
              String translated = translateMergeError(isMerge, e.getMessage(), mergePkColumns);
              if (translated != null) {
                throw new ScriptExecutionException(translated, e);
              }
              throw e;
            }
          }
        } else {
          // 기존 INSERT/UPDATE/DELETE는 그대로 실행. 이 경로의 preStatements 는 항상 빈 목록이다
          // (사용자 DML 은 위 REPLACE 판단에서 즉시 truncate 로 처리했고, 증분 전체 재생성 DELETE 도
          // isSelect 게이트가 걸려 여기로 오지 않는다) — 그래도 시그니처를 맞추기 위해 그대로 넘긴다.
          if (executorEnabled) {
            var result = executorClient.executeSql(sql, preStatements);
            if (!result.success()) {
              throw new ScriptExecutionException("SQL 실행 실패: " + result.error());
            }
            executionLog = result.executionLog();
          } else {
            executionLog = sqlExecutor.execute(preStatements, sql);
          }
        }
      } else if ("PYTHON".equals(step.scriptType())) {
        // 인가 게이트: 명시적 python_execute 권한 필요
        if (!permissionChecker.hasPermission(userId, "pipeline:python_execute")) {
          throw new ScriptExecutionException(
              "Python 스크립트 실행에는 'pipeline:python_execute' 권한이 필요합니다. " + "관리자에게 이 기능 활성화를 요청하세요.");
        }
        // escalation 코드 차단 — 저장 시 검증을 우회해 저장된 스텝(직접 DB 삽입 등)에 대한 실행 시 2차 방어 (#270)
        pythonScriptValidator.validate(step.scriptContent());
        // outputDatasetId가 없고 pythonConfig에 outputColumns가 있으면 임시 데이터셋 자동 생성
        if (outputDatasetId == null && step.pythonConfig() != null) {
          com.smartfirehub.pipeline.dto.PythonStepConfig pythonStepConfig =
              objectMapper.convertValue(
                  step.pythonConfig(), com.smartfirehub.pipeline.dto.PythonStepConfig.class);
          if (pythonStepConfig.outputColumns() != null
              && !pythonStepConfig.outputColumns().isEmpty()) {
            List<ColumnInfo> pythonColumns =
                pythonStepConfig.outputColumns().stream()
                    .map(col -> new ColumnInfo(col.name(), col.type()))
                    .toList();
            Long stepId = step.id();
            Optional<Long> existingDatasetId = tempDatasetService.findExistingTempDataset(stepId);
            if (existingDatasetId.isPresent()) {
              Long dsId = existingDatasetId.get();
              if (tempDatasetService.hasSchemaChanged(dsId, pythonColumns)) {
                log.info("Schema changed for Python step {}, recreating temp dataset", step.name());
                tempDatasetService.deleteTempDataset(dsId);
                outputDatasetId =
                    tempDatasetService.createTempDataset(
                        pythonColumns, pipelineId, pipelineName, stepId, step.name(), userId);
              } else {
                log.info("Reusing existing temp dataset {} for Python step {}", dsId, step.name());
                outputDatasetId = dsId;
              }
            } else {
              log.info("Creating new temp dataset for Python step {}", step.name());
              outputDatasetId =
                  tempDatasetService.createTempDataset(
                      pythonColumns, pipelineId, pipelineName, stepId, step.name(), userId);
            }
            outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          }
        }
        if (outputDatasetId == null) {
          log.warn("Python 스텝 '{}': 출력 데이터셋이 지정되지 않았습니다. 결과가 저장되지 않습니다.", step.name());
        }

        if (executorEnabled) {
          // 컬럼 타입 맵 구성 (API_CALL 블록과 동일 패턴)
          Map<String, String> columnTypeMap = null;
          if (outputDatasetId != null) {
            List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(outputDatasetId);
            columnTypeMap = new HashMap<>();
            for (DatasetColumnResponse col : columns) {
              columnTypeMap.put(col.columnName(), col.dataType());
            }
          }

          // REPLACE 전략: 임시 테이블 생성 후 swap (API_CALL 패턴과 동일)
          // 여기는 의도적으로 enum(strategy)이 아니라 원본 문자열을 본다 — strategy 는 알 수 없는 값을
          // REPLACE 로 폴백하지만, 맞바꿈 경로는 "명시적으로 REPLACE 라고 적힌" 경우에만 타야 한다.
          // 폴백을 여기까지 끌고 오면 알 수 없는 값이 갑자기 _tmp 생성·맞바꿈을 시작해 동작이 바뀐다.
          String targetTable = outputTableName;
          boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy) && outputTableName != null;
          if (isReplace) {
            dataTableService.createTempTable(outputTableName);
            targetTable = outputTableName + "_tmp";
          }
          try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("script", step.scriptContent());
            if (targetTable != null) {
              request.put("output_table", targetTable);
            }
            if (columnTypeMap != null) {
              request.put("column_type_map", columnTypeMap);
            }
            var result = executorClient.executePython(request);
            if (!result.success()) {
              throw new ScriptExecutionException("Python 실행 실패: " + result.error());
            }
            if (isReplace) {
              // stdout 에 JSON 이 없거나 0행이면 맞바꾸지 않고 원본을 유지한다 — 판단은
              // finishReplace 가 단독으로 갖는다(#685).
              dataTableService.finishReplace(outputTableName, result.rowsLoaded());
            }
            executionLog = result.output();
          } catch (Exception e) {
            if (isReplace) {
              try {
                dataTableService.dropTempTable(outputTableName);
              } catch (Exception dropEx) {
                log.warn(
                    "Failed to drop temp table after Python execution failure: {}",
                    dropEx.getMessage());
              }
            }
            throw e;
          }
        } else {
          executionLog = pythonExecutor.execute(step.scriptContent());
        }
      } else if ("API_CALL".equals(step.scriptType())) {
        ApiCallConfig apiCallConfig =
            objectMapper.convertValue(step.apiConfig(), ApiCallConfig.class);

        // apiConnectionId가 있으면 connection 정보 로드 (baseUrl + auth)
        ApiConnectionResponse apiConn = null;
        Map<String, String> decryptedAuth = null;
        if (step.apiConnectionId() != null) {
          apiConn = apiConnectionService.getById(step.apiConnectionId());
          decryptedAuth = apiConnectionService.getDecryptedAuthConfig(step.apiConnectionId());
        } else if (apiCallConfig.inlineAuth() != null) {
          decryptedAuth = apiCallConfig.inlineAuth();
        }

        // outputDatasetId가 없으면 임시 데이터셋 자동 생성
        if (outputDatasetId == null) {
          List<ColumnInfo> apiColumns = inferApiCallColumns(apiCallConfig);
          Long stepId = step.id();
          Optional<Long> existingDatasetId = tempDatasetService.findExistingTempDataset(stepId);
          if (existingDatasetId.isPresent()) {
            Long dsId = existingDatasetId.get();
            if (tempDatasetService.hasSchemaChanged(dsId, apiColumns)) {
              log.info("Schema changed for API_CALL step {}, recreating temp dataset", step.name());
              tempDatasetService.deleteTempDataset(dsId);
              outputDatasetId =
                  tempDatasetService.createTempDataset(
                      apiColumns, pipelineId, pipelineName, stepId, step.name(), userId);
            } else {
              log.info("Reusing existing temp dataset {} for API_CALL step {}", dsId, step.name());
              outputDatasetId = dsId;
            }
          } else {
            log.info("Creating new temp dataset for API_CALL step {}", step.name());
            outputDatasetId =
                tempDatasetService.createTempDataset(
                    apiColumns, pipelineId, pipelineName, stepId, step.name(), userId);
          }
          outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          // 여기서 truncateTable 하지 않는다 — 다시 넣지 말 것.
          // 이유 셋:
          //  (1) 로드 전략을 무시했다 — 재사용된 임시 데이터셋(ptmp_*)은 APPEND 스텝에서도 매 실행
          //      통째로 비워졌다. APPEND 는 절대 비우면 안 된다.
          //  (2) API 호출보다 **먼저** 커밋됐다(DataTableRowService 에는 @Transactional 이 없어
          //      truncate 가 즉시 커밋된다). 그 뒤 호출이 실패하면 출력 데이터셋이 빈 채로 남는다 —
          //      이 브랜치가 SQL 경로에서 없앤 바로 그 결함이다.
          //  (3) 빈 결과 가드(#685: "0행은 기존 데이터를 파괴하지 않는다")를 무력화했다. 아래
          //      finishReplace 가 0행이면 맞바꾸지 않고 원본을 지키는데, 앞에서 이미 비워 버리면
          //      지킬 원본이 없다.
          // REPLACE 비우기는 아래 두 경로가 이미 원자적으로 처리한다 — 실행기 켠 경로는
          // createTempTable → finishReplace 맞바꿈(이 블록 바로 아래), 끈 경로는 ApiCallExecutor
          // 내부의 동일한 t_tmp 맞바꿈이다. 실패 시 dropTempTable 로 이전 행이 그대로 남는다.
        }

        // 정확한 타입 변환을 위해 데이터셋 메타데이터에서 컬럼 타입 맵 구성
        Map<String, String> columnTypeMap = null;
        if (outputDatasetId != null) {
          List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(outputDatasetId);
          columnTypeMap = new HashMap<>();
          for (DatasetColumnResponse col : columns) {
            columnTypeMap.put(col.columnName(), col.dataType());
          }
        }

        if (executorEnabled) {
          // REPLACE 전략: API가 DDL 오케스트레이션 (executor는 INSERT만 수행)
          // PYTHON 블록과 같은 이유로 enum(strategy)이 아니라 원본 문자열을 본다 — 알 수 없는 값의
          // REPLACE 폴백을 맞바꿈 경로까지 끌고 오면 동작이 바뀐다.
          String targetTable = outputTableName;
          boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy) && outputTableName != null;
          if (isReplace) {
            dataTableService.createTempTable(outputTableName);
            targetTable = outputTableName + "_tmp";
          }
          try {
            Map<String, Object> request =
                buildApiCallExecutorRequest(
                    apiCallConfig, targetTable, decryptedAuth, columnTypeMap, apiConn);
            var result = executorClient.executeApiCall(request);
            if (!result.success()) {
              throw new ScriptExecutionException("API_CALL 실행 실패: " + result.error());
            }
            if (isReplace) {
              // 빈 결과 가드는 finishReplace 가 단독으로 갖는다(#685) — API 가 0행을 돌려주면
              // 맞바꾸지 않고 임시 테이블만 버려 원본을 지킨다. 예전에는 여기서 swapTable 을 바로
              // 불러 빈 테이블이 원본을 덮었다(같은 결함이 아직 터지지 않은 상태였다).
              dataTableService.finishReplace(outputTableName, result.rowsLoaded());
            }
            executionLog = result.executionLog();
          } catch (Exception e) {
            if (isReplace) {
              try {
                dataTableService.dropTempTable(outputTableName);
              } catch (Exception dropEx) {
                log.warn(
                    "Failed to drop temp table after API call failure: {}", dropEx.getMessage());
              }
            }
            throw e;
          }
        } else {
          ApiCallExecutor.ApiCallResult result =
              apiCallExecutor.execute(
                  apiCallConfig,
                  outputTableName,
                  decryptedAuth,
                  loadStrategy,
                  columnTypeMap,
                  apiConn);
          executionLog = result.log();
        }
      } else if ("AI_CLASSIFY".equals(step.scriptType())) {
        if (!permissionChecker.hasPermission(userId, "pipeline:ai_execute")) {
          throw new ScriptExecutionException(
              "AI 분류 스텝 실행에는 'pipeline:ai_execute' 권한이 필요합니다. 관리자에게 이 기능 활성화를 요청하세요.");
        }

        // 명시적 inputDatasetIds가 없으면 의존 스텝의 출력 데이터셋에서 자동 해결
        List<Long> resolvedInputDatasetIds = step.inputDatasetIds();
        if ((resolvedInputDatasetIds == null || resolvedInputDatasetIds.isEmpty())
            && step.dependsOnStepNames() != null
            && !step.dependsOnStepNames().isEmpty()) {

          List<PipelineStepResponse> allSteps = stepRepository.findByPipelineId(pipelineId);
          Map<String, PipelineStepResponse> stepByName =
              allSteps.stream().collect(Collectors.toMap(PipelineStepResponse::name, s -> s));

          resolvedInputDatasetIds = new ArrayList<>();
          for (String depName : step.dependsOnStepNames()) {
            PipelineStepResponse depStep = stepByName.get(depName);
            if (depStep == null) continue;

            Long depOutputId = depStep.outputDatasetId();
            if (depOutputId == null) {
              depOutputId = datasetRepository.findBySourcePipelineStepId(depStep.id()).orElse(null);
            }
            if (depOutputId != null) {
              resolvedInputDatasetIds.add(depOutputId);
            }
          }

          if (!resolvedInputDatasetIds.isEmpty()) {
            log.info(
                "[AI_CLASSIFY] Step '{}': Auto-resolved {} input dataset(s) from dependencies: {}",
                step.name(),
                resolvedInputDatasetIds.size(),
                resolvedInputDatasetIds);
          }
        }

        // outputDatasetId가 없으면 임시 데이터셋 자동 생성
        if (outputDatasetId == null) {
          com.smartfirehub.pipeline.dto.AiClassifyConfig aiClassifyConfig =
              objectMapper.convertValue(
                  step.aiConfig(), com.smartfirehub.pipeline.dto.AiClassifyConfig.class);
          List<ColumnInfo> aiColumns = buildAiClassifyColumns(aiClassifyConfig);
          Long stepId = step.id();
          Optional<Long> existingDatasetId = tempDatasetService.findExistingTempDataset(stepId);
          if (existingDatasetId.isPresent()) {
            Long dsId = existingDatasetId.get();
            if (tempDatasetService.hasSchemaChanged(dsId, aiColumns)) {
              log.info(
                  "Schema changed for AI_CLASSIFY step {}, recreating temp dataset", step.name());
              tempDatasetService.deleteTempDataset(dsId);
              outputDatasetId =
                  tempDatasetService.createTempDataset(
                      aiColumns, pipelineId, pipelineName, stepId, step.name(), userId);
            } else {
              log.info(
                  "Reusing existing temp dataset {} for AI_CLASSIFY step {}", dsId, step.name());
              outputDatasetId = dsId;
            }
          } else {
            log.info("Creating new temp dataset for AI_CLASSIFY step {}", step.name());
            outputDatasetId =
                tempDatasetService.createTempDataset(
                    aiColumns, pipelineId, pipelineName, stepId, step.name(), userId);
          }
          outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          // 여기서 truncateTable 하지 않는다 — 다시 넣지 말 것. API_CALL 블록과 같은 이유다:
          //  (1) APPEND 스텝의 재사용 임시 데이터셋까지 매 실행 비웠다.
          //  (2) AI 분류가 돌기 **전에** 즉시 커밋돼, 분류가 실패하면 출력이 빈 채로 남았다.
          //  (3) #685 의 빈 결과 가드를 무력화했다(지킬 원본을 미리 없애 버린다).
          // REPLACE 비우기는 AiClassifyExecutor 가 loadStrategy 를 직접 읽어 createTempTable →
          // swapTable 로 처리한다(AiClassifyExecutor:128-136, :268-269). 여기만 finishReplace 가
          // 아니라 swapTable 을 직접 부르는데, AI_CLASSIFY 에서 0행은 정상 결과가 아니라
          // :255 에서 먼저 예외를 던지기 때문이다 — 즉 swapTable 에 도달하면 이미 비어 있지 않다.
          // 실패하면 :273 의 dropTempTable 로 이전 행이 그대로 남는다.
        }

        // AiClassifyExecutor에 전달할 스텝 래퍼: 해결된 outputDatasetId 및 inputDatasetIds 반영
        final Long resolvedOutputDatasetId = outputDatasetId;
        final List<Long> finalInputDatasetIds = resolvedInputDatasetIds;
        PipelineStepResponse resolvedStep =
            new PipelineStepResponse(
                step.id(),
                step.name(),
                step.description(),
                step.scriptType(),
                step.scriptContent(),
                resolvedOutputDatasetId,
                step.outputDatasetName(),
                finalInputDatasetIds,
                step.dependsOnStepNames(),
                step.stepOrder(),
                step.loadStrategy(),
                step.apiConfig(),
                step.aiConfig(),
                step.pythonConfig(),
                step.apiConnectionId());

        AiClassifyExecutor.ExecutionResult aiResult =
            aiClassifyExecutor.execute(resolvedStep, stepExecId, userId);
        executionLog = aiResult.executionLog();
        // AI_CLASSIFY는 자체적으로 출력 행 수를 관리
      } else {
        throw new ScriptExecutionException("Unsupported script type: " + step.scriptType());
      }

      // 증분 책갈피 전진 — 반드시 "출력이 커밋된 뒤"다.
      //
      // 출력(테넌트 파이프라인 롤 커넥션)과 책갈피(앱 커넥션)는 서로 다른 커넥션이라 한 트랜잭션으로
      // 묶을 수 없다. 그래서 순서가 곧 안전장치다: 출력 커밋 → 책갈피 전진. 이 사이에서 죽으면 다음
      // 실행이 같은 구간을 다시 읽고, MERGE 의 멱등성이 그 중복을 흡수한다. 반대로 먼저 전진시키면
      // 출력 실패 시 그 구간이 영원히 비는 데이터 손실이 된다.
      //
      // 실패 경로(catch)에서는 전진하지 않는다 — 여기까지 도달하지 못하기 때문이다. 전체 재생성 예약은
      // "이번 실행이 실제로 전체 재생성이었을 때"만 해제한다(advanceCursor Javadoc) — 실패하면 예약이
      // 그대로 유지돼 다음 실행이 다시 시도하고, 이번 실행 도중 새로 켜진 예약도 잃지 않는다.
      if (incrementalStep) {
        stepRepository.advanceCursor(step.id(), stepCursorCandidate, stepWasFullRebuild);
      }

      // 출력 행 수 계산 (출력 테이블이 있는 경우)
      Long outputRows = null;
      if (outputTableName != null) {
        outputRows = dataTableRowService.countRows(outputTableName);
      }

      // 스텝 실행 완료 처리
      executionRepository.updateStepExecution(
          stepExecId,
          "COMPLETED",
          outputRows != null ? outputRows.intValue() : null,
          executionLog,
          null,
          null,
          LocalDateTime.now(ZoneOffset.UTC));

      log.info("Step {} completed successfully. Output rows: {}", step.name(), outputRows);
      return "COMPLETED";

    } catch (Exception e) {
      log.error("Step {} failed", step.name(), e);
      executionRepository.updateStepExecution(
          stepExecId,
          "FAILED",
          null,
          null,
          e.getMessage(),
          null,
          LocalDateTime.now(ZoneOffset.UTC));
      return "FAILED";
    }
  }

  /**
   * 외부 executor(Python 기반)로 전달할 API 호출 요청 Map을 구성한다. Phase 9: apiConn이 있으면 baseUrl+path로 최종 URL을
   * 계산하여 "url" 필드에 설정.
   */
  private Map<String, Object> buildApiCallExecutorRequest(
      ApiCallConfig config,
      String outputTable,
      Map<String, String> decryptedAuth,
      Map<String, String> columnTypeMap) {
    return buildApiCallExecutorRequest(config, outputTable, decryptedAuth, columnTypeMap, null);
  }

  private Map<String, Object> buildApiCallExecutorRequest(
      ApiCallConfig config,
      String outputTable,
      Map<String, String> decryptedAuth,
      Map<String, String> columnTypeMap,
      ApiConnectionResponse apiConn) {
    // URL 결정: ApiCallExecutor.resolveTargetUrl 규칙과 정확히 일치시킨다.
    // apiConn 설정 시 path 필수 — customUrl/url 폴백 금지(baseUrl 우회 방지).
    String resolvedUrl;
    if (apiConn != null) {
      if (config.path() == null || config.path().isBlank()) {
        throw new ScriptExecutionException("API_CALL: apiConnectionId 설정 시 path가 필수입니다");
      }
      resolvedUrl =
          com.smartfirehub.apiconnection.service.UrlUtils.joinUrl(apiConn.baseUrl(), config.path());
    } else if (config.customUrl() != null && !config.customUrl().isBlank()) {
      resolvedUrl = config.customUrl();
    } else if (config.url() != null && !config.url().isBlank()) {
      resolvedUrl = config.url();
    } else {
      throw new ScriptExecutionException(
          "API_CALL: apiConnectionId 없이 호출하려면 customUrl(또는 레거시 url)이 필수입니다");
    }

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("url", resolvedUrl);
    request.put("method", config.method() != null ? config.method() : "GET");
    if (config.headers() != null) request.put("headers", config.headers());
    if (config.queryParams() != null) request.put("query_params", config.queryParams());
    if (config.body() != null) request.put("body", config.body());
    request.put("data_path", config.dataPath());

    // 필드 매핑 변환
    List<Map<String, Object>> mappings = new ArrayList<>();
    if (config.fieldMappings() != null) {
      for (ApiCallConfig.FieldMapping fm : config.fieldMappings()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_field", fm.sourceField());
        m.put("target_column", fm.targetColumn());
        if (fm.dataType() != null) m.put("data_type", fm.dataType());
        if (fm.dateFormat() != null) m.put("date_format", fm.dateFormat());
        if (fm.numberFormat() != null) m.put("number_format", fm.numberFormat());
        if (fm.sourceTimezone() != null) m.put("source_timezone", fm.sourceTimezone());
        mappings.add(m);
      }
    }
    request.put("field_mappings", mappings);

    // 페이지네이션
    if (config.pagination() != null) {
      Map<String, Object> pag = new LinkedHashMap<>();
      pag.put("type", config.pagination().type());
      if (config.pagination().pageSize() != null)
        pag.put("page_size", config.pagination().pageSize());
      if (config.pagination().offsetParam() != null)
        pag.put("offset_param", config.pagination().offsetParam());
      if (config.pagination().limitParam() != null)
        pag.put("limit_param", config.pagination().limitParam());
      if (config.pagination().totalPath() != null)
        pag.put("total_path", config.pagination().totalPath());
      request.put("pagination", pag);
    }

    // 재시도 설정
    if (config.retry() != null) {
      Map<String, Object> retry = new LinkedHashMap<>();
      if (config.retry().maxRetries() != null)
        retry.put("max_retries", config.retry().maxRetries());
      if (config.retry().initialBackoffMs() != null)
        retry.put("initial_backoff_ms", config.retry().initialBackoffMs());
      if (config.retry().maxBackoffMs() != null)
        retry.put("max_backoff_ms", config.retry().maxBackoffMs());
      request.put("retry", retry);
    }

    if (config.timeoutMs() != null) request.put("timeout_ms", config.timeoutMs());
    if (config.maxDurationMs() != null) request.put("max_duration_ms", config.maxDurationMs());
    if (config.maxResponseSizeMb() != null)
      request.put("max_response_size_mb", config.maxResponseSizeMb());

    request.put("output_table", outputTable);
    // executor는 항상 APPEND (INSERT only) — 로드 전략은 API 레이어에서 처리
    request.put("load_strategy", "APPEND");

    if (columnTypeMap != null) request.put("column_type_map", columnTypeMap);
    if (decryptedAuth != null) request.put("auth", decryptedAuth);

    return request;
  }

  /**
   * API_CALL 스텝의 fieldMappings에서 임시 데이터셋 컬럼 목록을 추론한다.
   *
   * @param config API 호출 설정
   * @return 컬럼 정보 목록
   */
  private List<ColumnInfo> inferApiCallColumns(ApiCallConfig config) {
    if (config.fieldMappings() == null || config.fieldMappings().isEmpty()) {
      return List.of();
    }
    return config.fieldMappings().stream()
        .map(
            fm -> {
              String appType = "TEXT";
              if (fm.dataType() != null) {
                String dt = fm.dataType().toUpperCase();
                if (dt.contains("INT")) appType = "INTEGER";
                else if (dt.contains("NUMERIC")
                    || dt.contains("DECIMAL")
                    || dt.contains("FLOAT")
                    || dt.contains("DOUBLE")) appType = "DECIMAL";
                else if (dt.contains("BOOL")) appType = "BOOLEAN";
                else if (dt.equals("DATE")) appType = "DATE";
                else if (dt.contains("TIMESTAMP")) appType = "TIMESTAMP";
              }
              return new ColumnInfo(fm.targetColumn(), appType);
            })
        .collect(Collectors.toList());
  }

  /**
   * AI_CLASSIFY 스텝의 출력 컬럼 목록을 구성한다. source_id는 항상 첫 번째 컬럼으로 포함된다.
   *
   * @param config AI 분류 설정
   * @return 컬럼 정보 목록
   */
  private List<ColumnInfo> buildAiClassifyColumns(
      com.smartfirehub.pipeline.dto.AiClassifyConfig config) {
    List<ColumnInfo> columns = new ArrayList<>();
    // source_id는 입력 행 추적용으로 항상 첫 번째에 포함
    columns.add(new ColumnInfo("source_id", "INTEGER"));
    if (config.outputColumns() != null) {
      config.outputColumns().stream()
          .map(col -> new ColumnInfo(col.name(), col.type()))
          .forEach(columns::add);
    }
    return columns;
  }

  /**
   * SQL 내 {@code {{#N}}} 스텝 참조를 실제 테이블명으로 치환한다.
   *
   * @param sql 원본 SQL 문자열
   * @param pipelineId 파이프라인 ID
   * @param currentStep 현재 실행 중인 스텝 (자기 참조 방지 + 의존성 체인 검증에 사용)
   * @return 참조가 치환된 SQL 문자열
   */
  private String resolveStepReferences(String sql, Long pipelineId, PipelineStepResponse currentStep) {
    Pattern pattern = Pattern.compile("\\{\\{#(\\d+)\\}\\}");
    Matcher matcher = pattern.matcher(sql);
    if (!matcher.find()) {
      return sql;
    }

    List<PipelineStepResponse> allSteps = stepRepository.findByPipelineId(pipelineId);

    // 현재 스텝의 실제 선행(조상) 스텝 이름 집합 — dependsOnStepNames 체인을 재귀적으로 따라 수집한다.
    // {{#N}}의 N은 단순 배열 인덱스(step_order)일 뿐이라, "스텝 삽입"으로 DAG가 비선형이 되면
    // 인덱스 범위 검사만으로는 아직 실행되지 않은(산출물 없는) 후행 스텝까지 참조가 허용된다 (#531).
    Set<String> ancestorStepNames = collectAncestorStepNames(currentStep, allSteps);

    matcher.reset();
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      int stepNumber = Integer.parseInt(matcher.group(1)); // 1-based
      int stepIndex = stepNumber - 1; // 0-based

      if (stepIndex < 0 || stepIndex >= allSteps.size()) {
        throw new ScriptExecutionException(
            "{{#" + stepNumber + "}} 참조 실패: 스텝 번호 " + stepNumber + "이 존재하지 않습니다");
      }

      if (stepIndex == currentStep.stepOrder()) {
        throw new ScriptExecutionException("{{#" + stepNumber + "}} 참조 실패: 자기 자신을 참조할 수 없습니다");
      }

      PipelineStepResponse refStep = allSteps.get(stepIndex);

      if (!ancestorStepNames.contains(refStep.name())) {
        throw new ScriptExecutionException(
            "{{#"
                + stepNumber
                + "}} 참조 실패: 스텝 '"
                + refStep.name()
                + "'은(는) 현재 스텝의 선행 스텝(의존성 체인)이 아닙니다. 실행 순서상 먼저 실행되는 스텝만 참조할 수 있습니다");
      }

      Long datasetId = refStep.outputDatasetId();

      if (datasetId == null) {
        datasetId =
            datasetRepository
                .findBySourcePipelineStepId(refStep.id())
                .orElseThrow(
                    () ->
                        new ScriptExecutionException(
                            "{{#"
                                + stepNumber
                                + "}} 참조 실패: 스텝 '"
                                + refStep.name()
                                + "'의 출력 데이터셋이 아직 생성되지 않았습니다"));
      }

      final Long resolvedDatasetId = datasetId;
      String tableName =
          datasetRepository
              .findTableNameById(resolvedDatasetId)
              .orElseThrow(
                  () ->
                      new ScriptExecutionException(
                          "{{#" + stepNumber + "}} 참조 실패: 데이터셋 테이블을 찾을 수 없습니다"));

      // {{#n}} 참조를 실제 테이블 FQN 으로 치환한다 — 스키마는 현재 테넌트에서 파생시킨다.
      matcher.appendReplacement(
          result, Matcher.quoteReplacement(DataSchema.qualify(tableName)));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * 현재 스텝의 실제 선행(조상) 스텝 이름 집합을 dependsOnStepNames 체인을 따라 재귀적으로 수집한다.
   *
   * <p>{{#N}} 스텝 참조가 배열 인덱스만으로 임의의 스텝을 가리키지 못하도록, 실제 DAG 의존성 체인에 포함된
   * 스텝만 참조 가능하도록 검증하는 데 사용한다 (#531). 순환 참조가 있더라도 방문 집합으로 무한루프를 방지한다.
   *
   * @param currentStep 현재 실행 중인 스텝
   * @param allSteps 파이프라인의 전체 스텝 목록
   * @return 현재 스텝의 직접·간접 선행 스텝 이름 집합
   */
  private Set<String> collectAncestorStepNames(
      PipelineStepResponse currentStep, List<PipelineStepResponse> allSteps) {
    Map<String, PipelineStepResponse> stepByName = new HashMap<>();
    for (PipelineStepResponse s : allSteps) {
      stepByName.put(s.name(), s);
    }

    Set<String> visited = new HashSet<>();
    Deque<String> stack = new ArrayDeque<>();
    if (currentStep.dependsOnStepNames() != null) {
      stack.addAll(currentStep.dependsOnStepNames());
    }

    while (!stack.isEmpty()) {
      String name = stack.pop();
      if (name == null || visited.contains(name)) {
        continue;
      }
      visited.add(name);
      PipelineStepResponse depStep = stepByName.get(name);
      if (depStep != null && depStep.dependsOnStepNames() != null) {
        stack.addAll(depStep.dependsOnStepNames());
      }
    }

    return visited;
  }

  /**
   * SQL 문장이 SELECT(또는 CTE + SELECT)인지 판별한다.
   *
   * <p>WITH 절로 시작하는 CTE 구문은 본문 키워드(SELECT / INSERT / UPDATE / DELETE / MERGE)를 파싱하여 실제 DML 여부를
   * 확인한다.
   *
   * <p>패키지 전용 static — {@code PipelineService.saveSteps}가 MERGE 로드 전략은 SELECT 스텝에만
   * 허용된다는 저장 시점 검증(Fix round 1, must 3)에 같은 판별 로직을 재사용한다. 인스턴스 상태를 쓰지
   * 않는 순수 문자열 판별이라 static 으로 승격해도 동작이 바뀌지 않는다.
   */
  static boolean isSelectStatement(String sql) {
    String upper = sql.stripLeading().toUpperCase();
    if (upper.startsWith("SELECT")) {
      return true;
    }
    if (!upper.startsWith("WITH")) {
      return false;
    }
    return isCteFollowedBySelect(upper);
  }

  /**
   * WITH 절이 있는 SQL에서 CTE 정의를 건너뛴 후 본문이 SELECT인지 확인한다.
   *
   * <p>괄호 깊이를 추적하여 최상위 레벨에 도달한 뒤 첫 번째 키워드를 검사한다.
   */
  private static boolean isCteFollowedBySelect(String upperSql) {
    int depth = 0;
    int len = upperSql.length();
    int i = 0;

    while (i < len) {
      char c = upperSql.charAt(i);
      if (c == '(') {
        depth++;
        i++;
      } else if (c == ')') {
        depth--;
        i++;
      } else if (depth == 0) {
        // 최상위 레벨에서 키워드를 확인한다
        if (upperSql.startsWith("SELECT", i)) {
          return true;
        }
        if (upperSql.startsWith("INSERT", i)
            || upperSql.startsWith("UPDATE", i)
            || upperSql.startsWith("DELETE", i)
            || upperSql.startsWith("MERGE", i)) {
          return false;
        }
        i++;
      } else {
        i++;
      }
    }
    // 키워드를 찾지 못한 경우 안전하게 false 반환 (DML로 간주)
    return false;
  }

  /**
   * MERGE 실행 오류 메시지를 한국어 안내로 번역한다. 번역 대상이 아니면 {@code null}을 돌려줘 호출부가
   * 원본 오류를 그대로 쓰게 한다.
   *
   * <p>두 가지 PostgreSQL 오류 문구를 처리한다(Fix round 1, must 2 / must 4):
   *
   * <ul>
   *   <li>{@link MergeSqlBuilder#DUPLICATE_KEY_PG_MESSAGE} — SELECT 가 같은 PK 를 두 번 이상 낼 때.
   *   <li>{@link MergeSqlBuilder#NO_UNIQUE_CONSTRAINT_PG_MESSAGE} — {@code ux_<table>_pk} 인덱스가
   *       (동시 생성 실패 등으로) INVALID 상태라 메타데이터(is_primary_key=true)와 실제 제약이 어긋날 때.
   * </ul>
   *
   * <p>executor 켠 경로({@code result.error()})와 끈 경로({@code sqlExecutor.execute} 가 던지는
   * {@link ScriptExecutionException#getMessage()}) 양쪽이 이 메서드 하나를 공유한다 — 번역 문구가 두
   * 곳에서 갈리는 사고를 막는다.
   */
  private static String translateMergeError(boolean isMerge, String rawMessage, List<String> pkColumns) {
    if (!isMerge || rawMessage == null) {
      return null;
    }
    if (rawMessage.contains(MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE)) {
      return "SQL 결과에 같은 키(" + String.join(", ", pkColumns) + ")가 두 번 이상 나옵니다. "
          + "키별로 한 행만 나오도록 SQL을 수정하세요.";
    }
    if (rawMessage.contains(MergeSqlBuilder.NO_UNIQUE_CONSTRAINT_PG_MESSAGE)) {
      return "출력 데이터셋의 PK("
          + String.join(", ", pkColumns)
          + ") 유니크 인덱스가 유효하지 않습니다. 데이터셋 설정에서 PK 를 다시 지정한 뒤 다시 실행하세요.";
    }
    return null;
  }

  /**
   * SELECT 결과 컬럼명 중 시스템 예약어(id/import_id/created_at/_updated_at)와 충돌하는 이름을 자동으로 안전한 이름으로 바꾼다(#645).
   *
   * <p>{@code SELECT * FROM {{#N}}}처럼 이전 스텝(또는 실제 데이터셋)의 출력을 그대로 재사용하면, 모든 데이터셋 물리 테이블이
   * 자동으로 갖는 시스템 컬럼(id/created_at 등)이 결과 컬럼에 그대로 섞여 들어온다. 이를 새 임시 데이터셋의 "사용자 컬럼"으로
   * 그대로 저장하려 하면 {@code DataTableService}의 예약어 가드에 걸려 항상 실패한다. 그 가드는 사용자가 신규 데이터셋을 만들 때
   * 컬럼명을 직접 예약어로 짓는 것을 막기 위한 것이라 이 자동 패스스루 시나리오에는 부적합하므로, 여기서는 충돌하는 컬럼명에 순번
   * 접미사를 붙여 자동으로 별칭 처리한다 (예: {@code id} → {@code id_1}, {@code _updated_at} →
   * {@code updated_at_1}).
   *
   * <p><b>선행 밑줄을 반드시 떼고 접미사를 붙인다(코드리뷰 HIGH).</b> {@code DataTableService.validateName}
   * 은 컬럼명마다 {@code ^[a-z][a-z0-9_]*$} 를 요구한다 — 밑줄로 시작하는 이름은 거부다. 그래서
   * {@code _updated_at} 을 단순히 {@code _updated_at_1} 로 바꾸면 여전히 무효라 임시 데이터셋 생성이
   * {@code InvalidTableNameException} 으로 실패한다. V124 백필이 <b>모든</b> 데이터셋 테이블에
   * {@code _updated_at} 을 추가했으므로 {@code SELECT *} 스텝은 100% 이 경로를 탄다. 대소문자도 함께
   * 정규화한다 — 검증 정규식이 소문자만 허용하기 때문이다.
   *
   * <p>목록의 <b>개수와 순서는 절대 바꾸지 않는다</b> — 같은 목록이 뒤에서 INSERT 대상 컬럼 매칭에
   * 그대로 재사용되므로, 한 컬럼이라도 빠지면 SELECT 식 목록과 어긋나 실행이 깨진다.
   *
   * @param names SELECT 결과 컬럼명 목록 (순서 보존 필요 — INSERT 매칭에 그대로 재사용됨)
   * @return 예약어 충돌이 해소된 컬럼명 목록 (같은 순서, 같은 개수)
   */
  private static List<String> renameReservedColumnNames(List<String> names) {
    Set<String> used = new HashSet<>(names);
    List<String> result = new ArrayList<>();
    for (String name : names) {
      String candidate = name;
      // 예약 컬럼 집합은 DataTableService 와 공유한다 — 따로 복사해 두면 시스템 컬럼이 하나 늘었을 때
      // 이쪽만 조용히 낡는다(실제로 _updated_at 이 그렇게 추가됐다).
      if (DataTableService.SYSTEM_COLUMNS.contains(name.toLowerCase())) {
        // 접미사를 붙일 기반 이름 — 선행 밑줄 제거 + 소문자화로 항상 ^[a-z][a-z0-9_]*$ 를 만족시킨다.
        // (예약어 집합이 전부 영문자로 시작하므로 밑줄을 떼면 반드시 [a-z] 로 시작한다. 그래도
        // 방어적으로 빈 문자열이면 "col" 로 대체한다.)
        String base = name.toLowerCase().replaceFirst("^_+", "");
        if (base.isEmpty()) {
          base = "col";
        }
        int suffix = 1;
        candidate = base + "_" + suffix;
        // 사용자 컬럼에 이미 그 이름이 있으면 번호를 올려 충돌을 피한다(원래 이름 집합 + 이미 만든 별칭).
        while (used.contains(candidate)) {
          suffix++;
          candidate = base + "_" + suffix;
        }
      }
      used.add(candidate);
      result.add(candidate);
    }
    return result;
  }

  /** {@link #renameReservedColumnNames(List)}의 {@link ColumnInfo} 목록 버전 (타입 정보는 그대로 보존). */
  private static List<ColumnInfo> renameReservedColumns(List<ColumnInfo> columns) {
    List<String> renamedNames =
        renameReservedColumnNames(columns.stream().map(ColumnInfo::name).toList());
    List<ColumnInfo> result = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      result.add(new ColumnInfo(renamedNames.get(i), columns.get(i).appType()));
    }
    return result;
  }

}
