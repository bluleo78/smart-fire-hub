package com.smartfirehub.pipeline.dto;

import java.util.List;
import java.util.Map;

public record PipelineStepRequest(
    String name,
    String description,
    String scriptType, // "SQL", "PYTHON", "API_CALL", or "AI_CLASSIFY"
    String scriptContent, // nullable for API_CALL and AI_CLASSIFY
    Long outputDatasetId,
    List<Long> inputDatasetIds,
    List<String> dependsOnStepNames, // reference other steps by name
    String loadStrategy,
    Map<String, Object> apiConfig, // API_CALL configuration (JSON object)
    Map<String, Object> aiConfig, // AI_CLASSIFY configuration (JSON object)
    Long apiConnectionId // FK to api_connection
    ) {
  public PipelineStepRequest(
      String name,
      String description,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      List<Long> inputDatasetIds,
      List<String> dependsOnStepNames) {
    this(
        name,
        description,
        scriptType,
        scriptContent,
        outputDatasetId,
        inputDatasetIds,
        dependsOnStepNames,
        "REPLACE",
        null,
        null,
        null);
  }

  public PipelineStepRequest(
      String name,
      String description,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      List<Long> inputDatasetIds,
      List<String> dependsOnStepNames,
      String loadStrategy) {
    this(
        name,
        description,
        scriptType,
        scriptContent,
        outputDatasetId,
        inputDatasetIds,
        dependsOnStepNames,
        loadStrategy,
        null,
        null,
        null);
  }
}
