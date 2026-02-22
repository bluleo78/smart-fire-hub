package com.smartfirehub.pipeline.dto;

import java.util.List;
import java.util.Map;

public record PipelineStepRequest(
    String name,
    String description,
    String scriptType, // "SQL", "PYTHON", or "API_CALL"
    String scriptContent, // nullable for API_CALL
    Long outputDatasetId,
    List<Long> inputDatasetIds,
    List<String> dependsOnStepNames, // reference other steps by name
    String loadStrategy,
    Map<String, Object> apiConfig, // API_CALL configuration (JSON object)
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
        null);
  }
}
