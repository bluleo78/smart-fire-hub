package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
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
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PipelineExecutionService {

  private static final Logger log = LoggerFactory.getLogger(PipelineExecutionService.class);

  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineRepository pipelineRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final SqlScriptExecutor sqlExecutor;
  private final PythonScriptExecutor pythonExecutor;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final ApiCallExecutor apiCallExecutor;
  private final ApiConnectionService apiConnectionService;
  private final ObjectMapper objectMapper;
  private final PermissionChecker permissionChecker;
  private final ExecutorClient executorClient;

  @Value("${app.executor.enabled:false}")
  private boolean executorEnabled;

  public PipelineExecutionService(
      PipelineStepRepository stepRepository,
      PipelineExecutionRepository executionRepository,
      PipelineRepository pipelineRepository,
      DataTableService dataTableService,
      DataTableRowService dataTableRowService,
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      SqlScriptExecutor sqlExecutor,
      PythonScriptExecutor pythonExecutor,
      ApplicationEventPublisher applicationEventPublisher,
      ApiCallExecutor apiCallExecutor,
      ApiConnectionService apiConnectionService,
      ObjectMapper objectMapper,
      PermissionChecker permissionChecker,
      ExecutorClient executorClient) {
    this.stepRepository = stepRepository;
    this.executionRepository = executionRepository;
    this.pipelineRepository = pipelineRepository;
    this.dataTableService = dataTableService;
    this.dataTableRowService = dataTableRowService;
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.sqlExecutor = sqlExecutor;
    this.pythonExecutor = pythonExecutor;
    this.applicationEventPublisher = applicationEventPublisher;
    this.apiCallExecutor = apiCallExecutor;
    this.apiConnectionService = apiConnectionService;
    this.objectMapper = objectMapper;
    this.permissionChecker = permissionChecker;
    this.executorClient = executorClient;
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
    LocalDateTime executionStartedAt = LocalDateTime.now();
    Long pipelineCreatedBy = pipelineRepository.findCreatedByIdById(pipelineId).orElse(null);

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
              LocalDateTime.now());
          stepStatuses.put(step.id(), "SKIPPED");
          log.info("Step {} skipped due to failed dependency", step.name());
        } else {
          // Execute step
          String status = executeStep(stepExecId, step, userId);
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
          executionId, finalStatus, null, LocalDateTime.now());
      log.info("Pipeline execution {} completed with status: {}", executionId, finalStatus);

      // Publish completion event for chain triggers
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, finalStatus, pipelineCreatedBy));

    } catch (Exception e) {
      log.error("Pipeline execution {} failed with exception", executionId, e);
      executionRepository.updateExecutionStatus(executionId, "FAILED", null, LocalDateTime.now());

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

  private String executeStep(Long stepExecId, PipelineStepResponse step, Long userId) {
    LocalDateTime stepStartedAt = LocalDateTime.now();

    try {
      // Update step status to RUNNING
      executionRepository.updateStepExecution(
          stepExecId, "RUNNING", null, null, null, stepStartedAt, null);

      // Get output table name (nullable — metadata only)
      String outputTableName = null;
      if (step.outputDatasetId() != null) {
        outputTableName = datasetRepository.findTableNameById(step.outputDatasetId()).orElse(null);
      }

      // Apply load strategy before script execution
      // API_CALL skips this block — the executor handles load strategy internally
      String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";

      if (!"API_CALL".equals(step.scriptType())) {
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
        if (executorEnabled) {
          var result = executorClient.executeSql(step.scriptContent());
          if (!result.success()) {
            throw new ScriptExecutionException("SQL 실행 실패: " + result.error());
          }
          executionLog = result.executionLog();
        } else {
          executionLog = sqlExecutor.execute(step.scriptContent());
        }
      } else if ("PYTHON".equals(step.scriptType())) {
        // 인가 게이트: 명시적 python_execute 권한 필요
        if (!permissionChecker.hasPermission(userId, "pipeline:python_execute")) {
          throw new ScriptExecutionException(
              "Python 스크립트 실행에는 'pipeline:python_execute' 권한이 필요합니다. " + "관리자에게 이 기능 활성화를 요청하세요.");
        }
        if (executorEnabled) {
          var result = executorClient.executePython(step.scriptContent());
          if (!result.success()) {
            throw new ScriptExecutionException("Python 실행 실패: " + result.error());
          }
          executionLog = result.output();
        } else {
          executionLog = pythonExecutor.execute(step.scriptContent());
        }
      } else if ("API_CALL".equals(step.scriptType())) {
        ApiCallConfig apiCallConfig =
            objectMapper.convertValue(step.apiConfig(), ApiCallConfig.class);

        Map<String, String> decryptedAuth = null;
        if (step.apiConnectionId() != null) {
          decryptedAuth = apiConnectionService.getDecryptedAuthConfig(step.apiConnectionId());
        } else if (apiCallConfig.inlineAuth() != null) {
          decryptedAuth = apiCallConfig.inlineAuth();
        }

        // Build column type map from dataset metadata for accurate type conversion
        Map<String, String> columnTypeMap = null;
        if (step.outputDatasetId() != null) {
          List<DatasetColumnResponse> columns =
              columnRepository.findByDatasetId(step.outputDatasetId());
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
                    apiCallConfig, targetTable, decryptedAuth, columnTypeMap);
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
                  apiCallConfig, outputTableName, decryptedAuth, loadStrategy, columnTypeMap);
          executionLog = result.log();
        }
      } else {
        throw new ScriptExecutionException("Unsupported script type: " + step.scriptType());
      }

      // Count output rows (if output dataset specified)
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
          LocalDateTime.now());

      log.info("Step {} completed successfully. Output rows: {}", step.name(), outputRows);
      return "COMPLETED";

    } catch (Exception e) {
      log.error("Step {} failed", step.name(), e);
      executionRepository.updateStepExecution(
          stepExecId, "FAILED", null, null, e.getMessage(), null, LocalDateTime.now());
      return "FAILED";
    }
  }

  private Map<String, Object> buildApiCallExecutorRequest(
      ApiCallConfig config,
      String outputTable,
      Map<String, String> decryptedAuth,
      Map<String, String> columnTypeMap) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("url", config.url());
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
}
