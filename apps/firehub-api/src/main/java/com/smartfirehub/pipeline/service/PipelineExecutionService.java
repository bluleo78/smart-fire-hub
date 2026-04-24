package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.repository.UserRepository;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PipelineExecutionService {

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

  @Value("${app.executor.enabled:false}")
  private boolean executorEnabled;

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  public PipelineExecutionService(
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
      UserRepository userRepository,
      AuditLogService auditLogService) {
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
    this.userRepository = userRepository;
    this.auditLogService = auditLogService;
  }

  /**
   * Validate DAG using Kahn's algorithm for topological sort. Throws CyclicDependencyException if a
   * cycle is detected.
   */
  public void validateDAG(List<PipelineStepRequest> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }

    // Build step name to index map
    Map<String, Integer> stepNameToIndex = new HashMap<>();
    for (int i = 0; i < steps.size(); i++) {
      stepNameToIndex.put(steps.get(i).name(), i);
    }

    // Build adjacency list and in-degree map
    Map<Integer, List<Integer>> adjacencyList = new HashMap<>();
    Map<Integer, Integer> inDegree = new HashMap<>();

    for (int i = 0; i < steps.size(); i++) {
      adjacencyList.put(i, new ArrayList<>());
      inDegree.put(i, 0);
    }

    // Build graph from dependencies
    for (int i = 0; i < steps.size(); i++) {
      PipelineStepRequest step = steps.get(i);
      if (step.dependsOnStepNames() != null) {
        for (String dependsOnName : step.dependsOnStepNames()) {
          Integer dependsOnIndex = stepNameToIndex.get(dependsOnName);
          if (dependsOnIndex != null) {
            // dependsOnIndex -> i (dependency edge)
            adjacencyList.get(dependsOnIndex).add(i);
            inDegree.put(i, inDegree.get(i) + 1);
          }
        }
      }
    }

    // Kahn's algorithm
    Queue<Integer> queue = new LinkedList<>();

    // Add all nodes with in-degree 0
    for (Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.offer(entry.getKey());
      }
    }

    int processedCount = 0;

    while (!queue.isEmpty()) {
      int current = queue.poll();
      processedCount++;

      // Reduce in-degree for neighbors
      for (int neighbor : adjacencyList.get(current)) {
        inDegree.put(neighbor, inDegree.get(neighbor) - 1);
        if (inDegree.get(neighbor) == 0) {
          queue.offer(neighbor);
        }
      }
    }

    // If processed count != total nodes, there's a cycle
    if (processedCount != steps.size()) {
      throw new CyclicDependencyException("Cyclic dependency detected in pipeline steps");
    }
  }

  /** Execute pipeline asynchronously. */
  public Long executePipeline(Long pipelineId, Long userId) {
    return executePipeline(pipelineId, userId, "MANUAL", null);
  }

  /** Execute pipeline asynchronously with trigger info. */
  public Long executePipeline(Long pipelineId, Long userId, String triggeredBy, Long triggerId) {
    // Load pipeline steps and dependencies
    List<PipelineStepResponse> steps = stepRepository.findByPipelineId(pipelineId);

    // Create execution record
    Long executionId =
        executionRepository.createExecution(pipelineId, userId, triggeredBy, triggerId);

    // Create step execution records (all PENDING)
    Map<Long, Long> stepIdToStepExecId = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      Long stepExecId = executionRepository.createStepExecution(executionId, step.id());
      stepIdToStepExecId.put(step.id(), stepExecId);
    }

    // Build dependency map (stepId -> list of dependent step IDs)
    Map<Long, List<Long>> stepDependencyMap = buildDependencyMap(steps);

    // Execute asynchronously (userId threaded for Python permission check — @Async threads lack
    // SecurityContext)
    executeAsync(pipelineId, executionId, steps, stepDependencyMap, stepIdToStepExecId, userId);

    // 파이프라인 실행 감사 로그 (#60/#92)
    String pipelineNameForLog = pipelineRepository.findNameById(pipelineId).orElse("Pipeline");
    String usernameForLog = userRepository.findById(userId).map(u -> u.username()).orElse(null);
    auditLogService.log(userId, usernameForLog, "EXECUTE", "pipeline",
        String.valueOf(pipelineId), "파이프라인 실행: " + pipelineNameForLog,
        null, null, "SUCCESS", null, null);

    return executionId;
  }

  private Map<Long, List<Long>> buildDependencyMap(List<PipelineStepResponse> steps) {
    Map<String, Long> stepNameToId = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      stepNameToId.put(step.name(), step.id());
    }

    Map<Long, List<Long>> dependencyMap = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      List<Long> deps = new ArrayList<>();
      if (step.dependsOnStepNames() != null) {
        for (String depName : step.dependsOnStepNames()) {
          Long depStepId = stepNameToId.get(depName);
          if (depStepId != null) {
            deps.add(depStepId);
          }
        }
      }
      dependencyMap.put(step.id(), deps);
    }

    return dependencyMap;
  }

  @Async("pipelineExecutor")
  public void executeAsync(
      Long pipelineId,
      Long executionId,
      List<PipelineStepResponse> steps,
      Map<Long, List<Long>> stepDependencyMap,
      Map<Long, Long> stepIdToStepExecId,
      Long userId) {
    LocalDateTime executionStartedAt = LocalDateTime.now(ZoneOffset.UTC);
    Long pipelineCreatedBy = pipelineRepository.findCreatedByIdById(pipelineId).orElse(null);
    String pipelineName = pipelineRepository.findNameById(pipelineId).orElse("Pipeline");

    try {
      // Update execution status to RUNNING
      executionRepository.updateExecutionStatus(executionId, "RUNNING", executionStartedAt, null);

      // Topological sort to get execution order
      List<PipelineStepResponse> executionOrder = topologicalSort(steps, stepDependencyMap);

      // Track step statuses
      Map<Long, String> stepStatuses = new HashMap<>();

      // Execute steps in order
      for (PipelineStepResponse step : executionOrder) {
        Long stepExecId = stepIdToStepExecId.get(step.id());

        // Check if all dependencies completed successfully
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
          // Mark as SKIPPED
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
          // Execute step
          String status = executeStep(stepExecId, step, pipelineId, pipelineName, userId);
          stepStatuses.put(step.id(), status);
        }
      }

      // Determine overall execution status
      boolean allCompleted = stepStatuses.values().stream().allMatch(s -> "COMPLETED".equals(s));
      boolean anyFailed = stepStatuses.values().stream().anyMatch(s -> "FAILED".equals(s));

      String finalStatus;
      if (allCompleted) {
        finalStatus = "COMPLETED";
      } else if (anyFailed) {
        finalStatus = "FAILED";
      } else {
        finalStatus = "COMPLETED"; // Some skipped but no failures
      }

      executionRepository.updateExecutionStatus(
          executionId, finalStatus, null, LocalDateTime.now(ZoneOffset.UTC));
      log.info("Pipeline execution {} completed with status: {}", executionId, finalStatus);

      // Publish completion event for chain triggers
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, finalStatus, pipelineCreatedBy));

    } catch (Exception e) {
      log.error("Pipeline execution {} failed with exception", executionId, e);
      executionRepository.updateExecutionStatus(executionId, "FAILED", null, LocalDateTime.now(ZoneOffset.UTC));

      // Publish failure event for chain triggers
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, "FAILED", pipelineCreatedBy));
    }
  }

  private List<PipelineStepResponse> topologicalSort(
      List<PipelineStepResponse> steps, Map<Long, List<Long>> stepDependencyMap) {
    // Build reverse dependency map (child -> parents)
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

    // Kahn's algorithm
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

    // Map back to steps
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

  private String executeStep(
      Long stepExecId,
      PipelineStepResponse step,
      Long pipelineId,
      String pipelineName,
      Long userId) {
    LocalDateTime stepStartedAt = LocalDateTime.now(ZoneOffset.UTC);

    try {
      // Update step status to RUNNING
      executionRepository.updateStepExecution(
          stepExecId, "RUNNING", null, null, null, stepStartedAt, null);

      // Get output dataset ID (may be resolved to a temp dataset)
      Long outputDatasetId = step.outputDatasetId();

      // Get output table name (nullable — metadata only)
      String outputTableName = null;
      if (outputDatasetId != null) {
        outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElse(null);
      }

      // Apply load strategy before script execution
      // API_CALL skips this block — the executor handles load strategy internally
      String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";

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

      // Execute script based on type
      String executionLog;
      if ("SQL".equals(step.scriptType())) {
        String sql = step.scriptContent().trim();
        sql = resolveStepReferences(sql, pipelineId, step.stepOrder());
        boolean isSelect = isSelectStatement(sql);

        // Auto-create temp dataset when SELECT and no outputDatasetId
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

          Set<String> outputColumnNames =
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
        // Auto-create temp dataset when outputDatasetId is null and outputColumns defined
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
          // Build column type map (API_CALL 블록과 동일 패턴)
          Map<String, String> columnTypeMap = null;
          if (outputDatasetId != null) {
            List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(outputDatasetId);
            columnTypeMap = new HashMap<>();
            for (DatasetColumnResponse col : columns) {
              columnTypeMap.put(col.columnName(), col.dataType());
            }
          }

          // REPLACE: temp table + swap (API_CALL 패턴과 동일)
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

        // Auto-create temp dataset when outputDatasetId is null
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

        // Build column type map from dataset metadata for accurate type conversion
        Map<String, String> columnTypeMap = null;
        if (outputDatasetId != null) {
          List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(outputDatasetId);
          columnTypeMap = new HashMap<>();
          for (DatasetColumnResponse col : columns) {
            columnTypeMap.put(col.columnName(), col.dataType());
          }
        }

        if (executorEnabled) {
          // REPLACE: API orchestrates DDL (executor only does INSERT)
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
                  apiCallConfig, outputTableName, decryptedAuth, loadStrategy, columnTypeMap, apiConn);
          executionLog = result.log();
        }
      } else if ("AI_CLASSIFY".equals(step.scriptType())) {
        if (!permissionChecker.hasPermission(userId, "pipeline:ai_execute")) {
          throw new ScriptExecutionException(
              "AI 분류 스텝 실행에는 'pipeline:ai_execute' 권한이 필요합니다. 관리자에게 이 기능 활성화를 요청하세요.");
        }

        // Auto-resolve inputDatasetIds from dependency steps when not explicitly set
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

        // Auto-create temp dataset when outputDatasetId is null
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

        // Always wrap step with resolved outputDatasetId and inputDatasetIds for AiClassifyExecutor
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
        // AI_CLASSIFY manages its own output row counting
      } else {
        throw new ScriptExecutionException("Unsupported script type: " + step.scriptType());
      }

      // Count output rows (if output table resolved)
      Long outputRows = null;
      if (outputTableName != null) {
        outputRows = dataTableRowService.countRows(outputTableName);
      }

      // Update step execution (COMPLETED)
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
          stepExecId, "FAILED", null, null, e.getMessage(), null, LocalDateTime.now(ZoneOffset.UTC));
      return "FAILED";
    }
  }

  /**
   * 외부 executor(Python 기반)로 전달할 API 호출 요청 Map을 구성한다.
   * Phase 9: apiConn이 있으면 baseUrl+path로 최종 URL을 계산하여 "url" 필드에 설정.
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
        throw new ScriptExecutionException(
            "API_CALL: apiConnectionId 설정 시 path가 필수입니다");
      }
      resolvedUrl = com.smartfirehub.apiconnection.service.UrlUtils.joinUrl(
          apiConn.baseUrl(), config.path());
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

    // Convert field mappings
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

    // Pagination
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

    // Retry
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
    // executor always does APPEND (INSERT only) - load strategy is handled by API
    request.put("load_strategy", "APPEND");

    if (columnTypeMap != null) request.put("column_type_map", columnTypeMap);
    if (decryptedAuth != null) request.put("auth", decryptedAuth);

    return request;
  }

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

  private List<ColumnInfo> buildAiClassifyColumns(
      com.smartfirehub.pipeline.dto.AiClassifyConfig config) {
    List<ColumnInfo> columns = new ArrayList<>();
    // source_id is always included first for input row tracking
    columns.add(new ColumnInfo("source_id", "INTEGER"));
    if (config.outputColumns() != null) {
      config.outputColumns().stream()
          .map(col -> new ColumnInfo(col.name(), col.type()))
          .forEach(columns::add);
    }
    return columns;
  }

  private String resolveStepReferences(String sql, Long pipelineId, int currentStepIndex) {
    Pattern pattern = Pattern.compile("\\{\\{#(\\d+)\\}\\}");
    Matcher matcher = pattern.matcher(sql);
    if (!matcher.find()) {
      return sql;
    }

    List<PipelineStepResponse> allSteps = stepRepository.findByPipelineId(pipelineId);

    // Reset matcher to start from beginning
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

  private boolean isSelectStatement(String sql) {
    String upper = sql.stripLeading().toUpperCase();
    return upper.startsWith("SELECT") || upper.startsWith("WITH");
  }

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
