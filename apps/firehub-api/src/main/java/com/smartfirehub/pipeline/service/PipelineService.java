package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

  private static final Set<String> VALID_OUTPUT_COLUMN_TYPES =
      Set.of("TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP");
  private static final Set<String> VALID_ON_ERROR = Set.of("CONTINUE", "RETRY_BATCH", "FAIL_STEP");

  private final PipelineRepository pipelineRepository;
  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineExecutionService executionService;
  private final UserRepository userRepository;
  private final TriggerRepository triggerRepository;
  private final ObjectMapper objectMapper;

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
        if (stepRequest.apiConfig() == null || stepRequest.apiConfig().isEmpty()) {
          throw new IllegalArgumentException(
              "API_CALL step '" + stepRequest.name() + "' requires apiConfig");
        }
      }

      // Validate AI_CLASSIFY step requirements
      if ("AI_CLASSIFY".equals(stepRequest.scriptType())) {
        validateAiClassifyStep(stepRequest);
      }

      // Validate PYTHON step outputColumns
      if ("PYTHON".equals(stepRequest.scriptType()) && stepRequest.pythonConfig() != null) {
        validatePythonStep(stepRequest);
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

    if (step.aiConfig() == null || step.aiConfig().isEmpty()) {
      throw new IllegalArgumentException("AI_CLASSIFY step '" + stepName + "' requires aiConfig");
    }

    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);

    if (config.prompt() == null || config.prompt().isBlank()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires aiConfig.prompt");
    }
    if (config.outputColumns() == null || config.outputColumns().isEmpty()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires at least 1 outputColumn");
    }
    for (AiClassifyConfig.OutputColumn col : config.outputColumns()) {
      if (col.type() == null || !VALID_OUTPUT_COLUMN_TYPES.contains(col.type())) {
        throw new IllegalArgumentException(
            "AI_CLASSIFY step '"
                + stepName
                + "' outputColumn type must be one of: "
                + VALID_OUTPUT_COLUMN_TYPES);
      }
    }
    if (config.batchSize() != null && (config.batchSize() < 1 || config.batchSize() > 100)) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' batchSize must be between 1 and 100");
    }
    if (config.onError() != null && !VALID_ON_ERROR.contains(config.onError())) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' onError must be one of: " + VALID_ON_ERROR);
    }
  }

  private void validatePythonStep(PipelineStepRequest step) {
    PythonStepConfig config =
        objectMapper.convertValue(step.pythonConfig(), PythonStepConfig.class);
    if (config.outputColumns() != null) {
      for (PythonStepConfig.OutputColumn col : config.outputColumns()) {
        if (col.type() == null || !VALID_OUTPUT_COLUMN_TYPES.contains(col.type().toUpperCase())) {
          throw new IllegalArgumentException(
              "Python step '"
                  + step.name()
                  + "' outputColumn type must be one of: "
                  + VALID_OUTPUT_COLUMN_TYPES);
        }
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
