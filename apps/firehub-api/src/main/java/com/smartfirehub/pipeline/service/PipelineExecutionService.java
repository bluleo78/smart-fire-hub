package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PipelineExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutionService.class);

    private final PipelineStepRepository stepRepository;
    private final PipelineExecutionRepository executionRepository;
    private final DataTableService dataTableService;
    private final DatasetRepository datasetRepository;
    private final SqlScriptExecutor sqlExecutor;
    private final PythonScriptExecutor pythonExecutor;
    private final ApplicationEventPublisher applicationEventPublisher;

    public PipelineExecutionService(
            PipelineStepRepository stepRepository,
            PipelineExecutionRepository executionRepository,
            DataTableService dataTableService,
            DatasetRepository datasetRepository,
            SqlScriptExecutor sqlExecutor,
            PythonScriptExecutor pythonExecutor,
            ApplicationEventPublisher applicationEventPublisher) {
        this.stepRepository = stepRepository;
        this.executionRepository = executionRepository;
        this.dataTableService = dataTableService;
        this.datasetRepository = datasetRepository;
        this.sqlExecutor = sqlExecutor;
        this.pythonExecutor = pythonExecutor;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Validate DAG using Kahn's algorithm for topological sort.
     * Throws CyclicDependencyException if a cycle is detected.
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

    /**
     * Execute pipeline asynchronously.
     */
    public Long executePipeline(Long pipelineId, Long userId) {
        return executePipeline(pipelineId, userId, "MANUAL", null);
    }

    /**
     * Execute pipeline asynchronously with trigger info.
     */
    public Long executePipeline(Long pipelineId, Long userId, String triggeredBy, Long triggerId) {
        // Load pipeline steps and dependencies
        List<PipelineStepResponse> steps = stepRepository.findByPipelineId(pipelineId);

        // Create execution record
        Long executionId = executionRepository.createExecution(pipelineId, userId, triggeredBy, triggerId);

        // Create step execution records (all PENDING)
        Map<Long, Long> stepIdToStepExecId = new HashMap<>();
        for (PipelineStepResponse step : steps) {
            Long stepExecId = executionRepository.createStepExecution(executionId, step.id());
            stepIdToStepExecId.put(step.id(), stepExecId);
        }

        // Build dependency map (stepId -> list of dependent step IDs)
        Map<Long, List<Long>> stepDependencyMap = buildDependencyMap(steps);

        // Execute asynchronously
        executeAsync(pipelineId, executionId, steps, stepDependencyMap, stepIdToStepExecId);

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

    @Async
    public void executeAsync(Long pipelineId, Long executionId, List<PipelineStepResponse> steps, Map<Long, List<Long>> stepDependencyMap, Map<Long, Long> stepIdToStepExecId) {
        LocalDateTime executionStartedAt = LocalDateTime.now();

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
                    executionRepository.updateStepExecution(stepExecId, "SKIPPED", null, null, "Dependency failed or skipped", null, LocalDateTime.now());
                    stepStatuses.put(step.id(), "SKIPPED");
                    log.info("Step {} skipped due to failed dependency", step.name());
                } else {
                    // Execute step
                    String status = executeStep(stepExecId, step);
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

            executionRepository.updateExecutionStatus(executionId, finalStatus, null, LocalDateTime.now());
            log.info("Pipeline execution {} completed with status: {}", executionId, finalStatus);

            // Publish completion event for chain triggers
            applicationEventPublisher.publishEvent(new PipelineCompletedEvent(pipelineId, executionId, finalStatus));

        } catch (Exception e) {
            log.error("Pipeline execution {} failed with exception", executionId, e);
            executionRepository.updateExecutionStatus(executionId, "FAILED", null, LocalDateTime.now());

            // Publish failure event for chain triggers
            applicationEventPublisher.publishEvent(new PipelineCompletedEvent(pipelineId, executionId, "FAILED"));
        }
    }

    private List<PipelineStepResponse> topologicalSort(List<PipelineStepResponse> steps, Map<Long, List<Long>> stepDependencyMap) {
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

    private String executeStep(Long stepExecId, PipelineStepResponse step) {
        LocalDateTime stepStartedAt = LocalDateTime.now();

        try {
            // Update step status to RUNNING
            executionRepository.updateStepExecution(stepExecId, "RUNNING", null, null, null, stepStartedAt, null);

            // Get output table name (nullable — metadata only)
            String outputTableName = null;
            if (step.outputDatasetId() != null) {
                outputTableName = datasetRepository.findTableNameById(step.outputDatasetId())
                        .orElse(null);
            }

            // Apply load strategy before script execution
            String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";

            switch (loadStrategy) {
                case "REPLACE":
                    if (outputTableName != null) {
                        log.info("REPLACE strategy: Truncating output table: {}", outputTableName);
                        dataTableService.truncateTable(outputTableName);
                    }
                    break;
                case "APPEND":
                    log.info("APPEND strategy: Skipping truncation for output table: {}", outputTableName);
                    break;
                default:
                    log.warn("Unknown load strategy '{}', falling back to REPLACE", loadStrategy);
                    if (outputTableName != null) {
                        dataTableService.truncateTable(outputTableName);
                    }
                    break;
            }

            // Execute script based on type
            String executionLog;
            if ("SQL".equals(step.scriptType())) {
                executionLog = sqlExecutor.execute(step.scriptContent());
            } else if ("PYTHON".equals(step.scriptType())) {
                executionLog = pythonExecutor.execute(step.scriptContent());
            } else {
                throw new ScriptExecutionException("Unsupported script type: " + step.scriptType());
            }

            // Count output rows (if output dataset specified)
            Long outputRows = null;
            if (outputTableName != null) {
                outputRows = dataTableService.countRows(outputTableName);
            }

            // Update step execution (COMPLETED)
            executionRepository.updateStepExecution(
                    stepExecId,
                    "COMPLETED",
                    outputRows != null ? outputRows.intValue() : null,
                    executionLog,
                    null,
                    null,
                    LocalDateTime.now()
            );

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
                    LocalDateTime.now()
            );
            return "FAILED";
        }
    }
}
