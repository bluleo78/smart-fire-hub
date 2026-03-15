package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PipelineService {

  private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

  private static final Set<String> VALID_ON_LOW_CONFIDENCE =
      Set.of("MARK_UNKNOWN", "KEEP_BEST_LABEL", "FAIL_STEP");
  private static final Set<String> VALID_ON_ERROR = Set.of("CONTINUE", "RETRY_BATCH", "FAIL_STEP");

  private final PipelineRepository pipelineRepository;
  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineExecutionService executionService;
  private final UserRepository userRepository;
  private final TriggerRepository triggerRepository;
  private final DatasetColumnRepository datasetColumnRepository;
  private final ObjectMapper objectMapper;

  public PipelineService(
      PipelineRepository pipelineRepository,
      PipelineStepRepository stepRepository,
      PipelineExecutionRepository executionRepository,
      PipelineExecutionService executionService,
      UserRepository userRepository,
      TriggerRepository triggerRepository,
      DatasetColumnRepository datasetColumnRepository,
      ObjectMapper objectMapper) {
    this.pipelineRepository = pipelineRepository;
    this.stepRepository = stepRepository;
    this.executionRepository = executionRepository;
    this.executionService = executionService;
    this.userRepository = userRepository;
    this.triggerRepository = triggerRepository;
    this.datasetColumnRepository = datasetColumnRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public PipelineDetailResponse createPipeline(CreatePipelineRequest request, Long userId) {
    // Validate DAG (cycle detection)
    executionService.validateDAG(request.steps());

    // Save pipeline
    PipelineResponse pipeline =
        pipelineRepository.save(request.name(), request.description(), userId);

    // Save steps
    saveSteps(pipeline.id(), request.steps());

    // Return full detail
    return getPipelineById(pipeline.id());
  }

  private void saveSteps(Long pipelineId, List<PipelineStepRequest> stepRequests) {
    if (stepRequests == null || stepRequests.isEmpty()) {
      return;
    }

    // Step 1: Save all steps and build name -> stepId map
    Map<String, Long> stepNameToId = new HashMap<>();

    for (int i = 0; i < stepRequests.size(); i++) {
      PipelineStepRequest stepRequest = stepRequests.get(i);

      // Validate API_CALL step requirements
      if ("API_CALL".equals(stepRequest.scriptType())) {
        if (stepRequest.outputDatasetId() == null) {
          throw new IllegalArgumentException(
              "API_CALL step '" + stepRequest.name() + "' requires outputDatasetId");
        }
        if (stepRequest.apiConfig() == null || stepRequest.apiConfig().isEmpty()) {
          throw new IllegalArgumentException(
              "API_CALL step '" + stepRequest.name() + "' requires apiConfig");
        }
      }

      // Validate AI_CLASSIFY step requirements
      if ("AI_CLASSIFY".equals(stepRequest.scriptType())) {
        validateAiClassifyStep(stepRequest);
      }

      // Save step
      Long stepId = stepRepository.saveStep(pipelineId, stepRequest, i);
      stepNameToId.put(stepRequest.name(), stepId);

      // Save input datasets
      if (stepRequest.inputDatasetIds() != null) {
        for (Long datasetId : stepRequest.inputDatasetIds()) {
          stepRepository.saveStepInput(stepId, datasetId);
        }
      }
    }

    // Step 2: Save dependencies (now that all steps exist)
    for (PipelineStepRequest stepRequest : stepRequests) {
      Long stepId = stepNameToId.get(stepRequest.name());

      if (stepRequest.dependsOnStepNames() != null) {
        for (String dependsOnStepName : stepRequest.dependsOnStepNames()) {
          Long dependsOnStepId = stepNameToId.get(dependsOnStepName);
          if (dependsOnStepId != null) {
            stepRepository.saveStepDependency(stepId, dependsOnStepId);
          }
        }
      }
    }
  }

  @Transactional(readOnly = true)
  public PageResponse<PipelineResponse> getPipelines(int page, int size) {
    List<PipelineResponse> content = pipelineRepository.findAll(page, size);
    long totalElements = pipelineRepository.count();
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }

  @Transactional(readOnly = true)
  public PipelineDetailResponse getPipelineById(Long id) {
    PipelineResponse pipeline =
        pipelineRepository
            .findById(id)
            .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + id));

    List<PipelineStepResponse> steps = stepRepository.findByPipelineId(id);

    var updatedAt = pipelineRepository.findUpdatedAtById(id).orElse(null);

    String updatedByUsername =
        pipelineRepository
            .findUpdatedByById(id)
            .flatMap(userRepository::findById)
            .map(user -> user.name())
            .orElse(null);

    return new PipelineDetailResponse(
        pipeline.id(),
        pipeline.name(),
        pipeline.description(),
        pipeline.isActive(),
        pipeline.createdBy(),
        steps,
        pipeline.createdAt(),
        updatedAt,
        updatedByUsername);
  }

  @Transactional
  public void updatePipeline(Long id, UpdatePipelineRequest request, Long userId) {
    // Verify pipeline exists
    pipelineRepository
        .findById(id)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + id));

    // Validate DAG if steps provided
    if (request.steps() != null) {
      executionService.validateDAG(request.steps());
    }

    // Update pipeline metadata
    pipelineRepository.update(
        id, request.name(), request.description(), request.isActive(), userId);

    // Delete old steps and save new ones (full replacement)
    if (request.steps() != null) {
      stepRepository.deleteByPipelineId(id);
      saveSteps(id, request.steps());
    }
  }

  private void validateAiClassifyStep(PipelineStepRequest step) {
    String stepName = step.name();

    if (step.outputDatasetId() == null) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires outputDatasetId");
    }
    if (step.aiConfig() == null || step.aiConfig().isEmpty()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires aiConfig");
    }

    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);

    if (config.sourceColumn() == null || config.sourceColumn().isBlank()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires aiConfig.sourceColumn");
    }
    if (config.keyColumn() == null || config.keyColumn().isBlank()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires aiConfig.keyColumn");
    }
    if (config.labels() == null || config.labels().size() < 2) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires at least 2 labels");
    }
    if (config.batchSize() != null && (config.batchSize() < 1 || config.batchSize() > 100)) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' batchSize must be between 1 and 100");
    }
    if (config.confidenceThreshold() != null
        && (config.confidenceThreshold() < 0.0 || config.confidenceThreshold() > 1.0)) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' confidenceThreshold must be between 0.0 and 1.0");
    }
    if (config.onLowConfidence() != null
        && !VALID_ON_LOW_CONFIDENCE.contains(config.onLowConfidence())) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '"
              + stepName
              + "' onLowConfidence must be one of: "
              + VALID_ON_LOW_CONFIDENCE);
    }
    if (config.onError() != null && !VALID_ON_ERROR.contains(config.onError())) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '"
              + stepName
              + "' onError must be one of: "
              + VALID_ON_ERROR);
    }

    // Validate sourceColumn and keyColumn exist in input datasets
    if (step.inputDatasetIds() != null && !step.inputDatasetIds().isEmpty()) {
      Set<String> allColumnNames =
          step.inputDatasetIds().stream()
              .flatMap(
                  datasetId ->
                      datasetColumnRepository.findByDatasetId(datasetId).stream()
                          .map(col -> col.columnName()))
              .collect(Collectors.toSet());

      if (!allColumnNames.contains(config.sourceColumn())) {
        throw new IllegalArgumentException(
            "AI_CLASSIFY step '"
                + stepName
                + "' sourceColumn '"
                + config.sourceColumn()
                + "' not found in input datasets");
      }
      if (!allColumnNames.contains(config.keyColumn())) {
        throw new IllegalArgumentException(
            "AI_CLASSIFY step '"
                + stepName
                + "' keyColumn '"
                + config.keyColumn()
                + "' not found in input datasets");
      }
    }
  }

  @Transactional
  public void deletePipeline(Long id) {
    // Verify pipeline exists
    pipelineRepository
        .findById(id)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + id));

    // Disable chain triggers that reference this pipeline as upstream
    int disabled = triggerRepository.disableByUpstreamPipelineId(id);
    if (disabled > 0) {
      log.info("Disabled {} chain triggers referencing pipeline {}", disabled, id);
    }

    // Delete steps (cascade deletes inputs and dependencies)
    stepRepository.deleteByPipelineId(id);

    // Delete pipeline
    pipelineRepository.deleteById(id);
  }

  public PipelineExecutionResponse executePipeline(Long pipelineId, Long userId) {
    return executePipeline(pipelineId, userId, "MANUAL", null);
  }

  public PipelineExecutionResponse executePipeline(
      Long pipelineId, Long userId, String triggeredBy, Long triggerId) {
    // Verify pipeline exists
    pipelineRepository
        .findById(pipelineId)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + pipelineId));

    // Get user display name
    String username =
        userRepository.findById(userId).map(user -> user.name()).orElse(String.valueOf(userId));

    // Start execution with trigger info
    Long executionId = executionService.executePipeline(pipelineId, userId, triggeredBy, triggerId);

    // Return execution response
    return new PipelineExecutionResponse(
        executionId,
        pipelineId,
        "PENDING",
        username,
        null,
        null,
        java.time.LocalDateTime.now(),
        triggeredBy,
        null);
  }

  @Transactional(readOnly = true)
  public List<PipelineExecutionResponse> getExecutionsByPipelineId(Long pipelineId) {
    // Verify pipeline exists
    pipelineRepository
        .findById(pipelineId)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + pipelineId));

    return executionRepository.findExecutionsByPipelineId(pipelineId);
  }

  @Transactional(readOnly = true)
  public ExecutionDetailResponse getExecutionById(Long pipelineId, Long executionId) {
    // Verify pipeline exists
    pipelineRepository
        .findById(pipelineId)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + pipelineId));

    return executionRepository
        .findExecutionById(executionId)
        .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
  }
}
