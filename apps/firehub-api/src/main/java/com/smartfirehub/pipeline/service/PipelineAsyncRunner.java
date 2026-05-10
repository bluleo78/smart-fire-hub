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
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class PipelineAsyncRunner {

  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineRepository pipelineRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final DSLContext pipelineDsl;
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

  /** {@code @Qualifier("pipelineDslContext")}가 필요하여 명시적 생성자 주입을 사용한다. */
  public PipelineAsyncRunner(
      PipelineStepRepository stepRepository,
      PipelineExecutionRepository executionRepository,
      PipelineRepository pipelineRepository,
      DataTableService dataTableService,
      DataTableRowService dataTableRowService,
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      @Qualifier("pipelineDslContext") DSLContext pipelineDsl,
      SqlScriptExecutor sqlExecutor,
      PythonScriptExecutor pythonExecutor,
      ApplicationEventPublisher applicationEventPublisher,
      ApiCallExecutor apiCallExecutor,
      ApiConnectionService apiConnectionService,
      ObjectMapper objectMapper,
      PermissionChecker permissionChecker,
      ExecutorClient executorClient,
      AiClassifyExecutor aiClassifyExecutor,
      TempDatasetService tempDatasetService,
      SqlValidator sqlValidator) {
    this.stepRepository = stepRepository;
    this.executionRepository = executionRepository;
    this.pipelineRepository = pipelineRepository;
    this.dataTableService = dataTableService;
    this.dataTableRowService = dataTableRowService;
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.pipelineDsl = pipelineDsl;
    this.sqlExecutor = sqlExecutor;
    this.pythonExecutor = pythonExecutor;
    this.applicationEventPublisher = applicationEventPublisher;
    this.apiCallExecutor = apiCallExecutor;
    this.apiConnectionService = apiConnectionService;
    this.objectMapper = objectMapper;
    this.permissionChecker = permissionChecker;
    this.executorClient = executorClient;
    this.aiClassifyExecutor = aiClassifyExecutor;
    this.tempDatasetService = tempDatasetService;
    this.sqlValidator = sqlValidator;
  }

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

      // 체인 트리거를 위한 완료 이벤트 발행
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, finalStatus, pipelineCreatedBy));

    } catch (Exception e) {
      log.error("Pipeline execution {} failed with exception", executionId, e);
      executionRepository.updateExecutionStatus(
          executionId, "FAILED", null, LocalDateTime.now(ZoneOffset.UTC));

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

      // 로드 전략 결정 (기본: REPLACE)
      String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";

      // API_CALL, AI_CLASSIFY, 외부 Python executor는 내부에서 로드 전략을 처리하므로 여기서는 스킵
      if (!"API_CALL".equals(step.scriptType())
          && !"AI_CLASSIFY".equals(step.scriptType())
          && !(executorEnabled && "PYTHON".equals(step.scriptType()))) {
        switch (loadStrategy) {
          case "REPLACE":
            if (outputTableName != null) {
              log.info("REPLACE strategy: Truncating output table: {}", outputTableName);
              dataTableRowService.truncateTable(outputTableName);
            }
            break;
          case "APPEND":
            log.info("APPEND strategy: Skipping truncation for output table: {}", outputTableName);
            break;
          default:
            log.warn("Unknown load strategy '{}', falling back to REPLACE", loadStrategy);
            if (outputTableName != null) {
              dataTableRowService.truncateTable(outputTableName);
            }
            break;
        }
      }

      // 스크립트 타입별 실행
      String executionLog;
      if ("SQL".equals(step.scriptType())) {
        String sql = step.scriptContent().trim();
        sql = resolveStepReferences(sql, pipelineId, step.stepOrder());
        // 실행 직전 재검증 — 저장 이후 정책 변경/우회 방지. probe/wrappedSql 결합은 이 검증 통과 후이므로
        // 단일 statement·세미콜론 없음이 보장되어 구조적으로 안전하다. (#136)
        sqlValidator.validate(sql);
        boolean isSelect = isSelectStatement(sql);

        // SELECT이고 outputDatasetId가 없으면 임시 데이터셋 자동 생성
        if (isSelect && outputDatasetId == null) {
          Long stepId = step.id();
          List<ColumnInfo> selectColumns = extractSelectColumnsWithTypes(sql);

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
          dataTableRowService.truncateTable(outputTableName);
        }

        if (isSelect && outputTableName != null && outputDatasetId != null) {
          // SELECT 자동 적재: 컬럼 추출 → 매칭 → INSERT INTO ... SELECT 래핑
          List<String> selectColumns = extractSelectColumns(sql);

          java.util.Set<String> outputColumnNames =
              columnRepository.findByDatasetId(outputDatasetId).stream()
                  .filter(col -> !col.isPrimaryKey())
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

          String columnList =
              matchedColumns.stream()
                  .map(col -> "\"" + col + "\"")
                  .collect(Collectors.joining(", "));
          String wrappedSql =
              "INSERT INTO data.\"" + outputTableName + "\" (" + columnList + ") " + sql;

          if (executorEnabled) {
            var result = executorClient.executeSql(wrappedSql);
            if (!result.success()) {
              throw new ScriptExecutionException("SQL 실행 실패: " + result.error());
            }
            executionLog = result.executionLog();
          } else {
            executionLog = sqlExecutor.execute(wrappedSql);
          }
        } else {
          // 기존 INSERT/UPDATE/DELETE는 그대로 실행
          if (executorEnabled) {
            var result = executorClient.executeSql(sql);
            if (!result.success()) {
              throw new ScriptExecutionException("SQL 실행 실패: " + result.error());
            }
            executionLog = result.executionLog();
          } else {
            executionLog = sqlExecutor.execute(sql);
          }
        }
      } else if ("PYTHON".equals(step.scriptType())) {
        // 인가 게이트: 명시적 python_execute 권한 필요
        if (!permissionChecker.hasPermission(userId, "pipeline:python_execute")) {
          throw new ScriptExecutionException(
              "Python 스크립트 실행에는 'pipeline:python_execute' 권한이 필요합니다. " + "관리자에게 이 기능 활성화를 요청하세요.");
        }
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
            if (isReplace && result.rowsLoaded() > 0) {
              dataTableService.swapTable(outputTableName);
            } else if (isReplace) {
              // stdout에 JSON 없거나 0행 → temp table 삭제, 원본 유지
              dataTableService.dropTempTable(outputTableName);
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
          dataTableRowService.truncateTable(outputTableName);
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
              dataTableService.swapTable(outputTableName);
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
          dataTableRowService.truncateTable(outputTableName);
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
   * @param currentStepIndex 현재 스텝의 0-based 인덱스 (자기 참조 방지)
   * @return 참조가 치환된 SQL 문자열
   */
  private String resolveStepReferences(String sql, Long pipelineId, int currentStepIndex) {
    Pattern pattern = Pattern.compile("\\{\\{#(\\d+)\\}\\}");
    Matcher matcher = pattern.matcher(sql);
    if (!matcher.find()) {
      return sql;
    }

    List<PipelineStepResponse> allSteps = stepRepository.findByPipelineId(pipelineId);

    matcher.reset();
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      int stepNumber = Integer.parseInt(matcher.group(1)); // 1-based
      int stepIndex = stepNumber - 1; // 0-based

      if (stepIndex < 0 || stepIndex >= allSteps.size()) {
        throw new ScriptExecutionException(
            "{{#" + stepNumber + "}} 참조 실패: 스텝 번호 " + stepNumber + "이 존재하지 않습니다");
      }

      if (stepIndex == currentStepIndex) {
        throw new ScriptExecutionException("{{#" + stepNumber + "}} 참조 실패: 자기 자신을 참조할 수 없습니다");
      }

      PipelineStepResponse refStep = allSteps.get(stepIndex);
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

      matcher.appendReplacement(result, Matcher.quoteReplacement("data.\"" + tableName + "\""));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * SQL 문장이 SELECT(또는 CTE + SELECT)인지 판별한다.
   *
   * <p>WITH 절로 시작하는 CTE 구문은 본문 키워드(SELECT / INSERT / UPDATE / DELETE / MERGE)를 파싱하여 실제 DML 여부를
   * 확인한다.
   */
  private boolean isSelectStatement(String sql) {
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
  private boolean isCteFollowedBySelect(String upperSql) {
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
   * SQL SELECT 문의 컬럼명 목록을 추출한다. 실제 DB에 probe 쿼리를 실행하여 정확한 컬럼명을 얻는다.
   *
   * @param sql SELECT SQL 문
   * @return 컬럼명 목록
   */
  private List<String> extractSelectColumns(String sql) {
    try {
      String probeSql = "SELECT * FROM (" + sql + ") AS _probe LIMIT 0";
      return Arrays.stream(pipelineDsl.fetch(probeSql).fields())
          .map(Field::getName)
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new ScriptExecutionException("SQL 컬럼 분석 실패: " + e.getMessage(), e);
    }
  }

  /**
   * SQL SELECT 문의 컬럼명과 타입 목록을 추출한다. 임시 데이터셋 스키마 생성에 사용된다.
   *
   * @param sql SELECT SQL 문
   * @return 컬럼 정보(이름, 타입) 목록
   */
  private List<ColumnInfo> extractSelectColumnsWithTypes(String sql) {
    try {
      String probeSql = "SELECT * FROM (" + sql + ") AS _probe LIMIT 0";
      var result = pipelineDsl.fetch(probeSql);
      return Arrays.stream(result.fields())
          .map(f -> new ColumnInfo(f.getName(), mapJooqTypeToAppType(f.getDataType())))
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new ScriptExecutionException("SQL 컬럼 타입 분석 실패: " + e.getMessage(), e);
    }
  }

  /**
   * jOOQ DataType을 애플리케이션 타입 문자열로 변환한다.
   *
   * @param dataType jOOQ 데이터 타입
   * @return 애플리케이션 타입 문자열 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP)
   */
  private String mapJooqTypeToAppType(org.jooq.DataType<?> dataType) {
    String sqlType = dataType.getTypeName().toUpperCase();
    if (sqlType.contains("VARCHAR") || sqlType.contains("TEXT") || sqlType.contains("CHAR"))
      return "TEXT";
    if (sqlType.contains("INT") || sqlType.contains("SERIAL")) return "INTEGER";
    if (sqlType.contains("NUMERIC")
        || sqlType.contains("DECIMAL")
        || sqlType.contains("FLOAT")
        || sqlType.contains("DOUBLE")) return "DECIMAL";
    if (sqlType.contains("BOOL")) return "BOOLEAN";
    if (sqlType.equals("DATE")) return "DATE";
    if (sqlType.contains("TIMESTAMP")) return "TIMESTAMP";
    return "TEXT";
  }
}
