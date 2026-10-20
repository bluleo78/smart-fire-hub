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
    Map<String, Object> pythonConfig, // PYTHON configuration (JSON object)
    Long apiConnectionId, // FK to api_connection
    /**
     * 기존 스텝 id(WD-108) — 수정 저장 시 "같은 스텝"을 가리킨다. 오면 그 스텝을 제자리 갱신(id 유지)해 과거 실행 기록이 그대로 이어지고, 이름을 바꿔도
     * 같은 스텝으로 남는다. 없으면(새 스텝·id 를 모르는 클라이언트) 파이프라인 안에서 고유한 이름으로 기존 스텝을 찾는다. 생성 요청에서는 무시한다.
     */
    Long id) {
  /** id 없는 요청(새 스텝·이름으로 식별하는 클라이언트)용 — WD-108 이전 시그니처를 유지한다. */
  public PipelineStepRequest(
      String name,
      String description,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      List<Long> inputDatasetIds,
      List<String> dependsOnStepNames,
      String loadStrategy,
      Map<String, Object> apiConfig,
      Map<String, Object> aiConfig,
      Map<String, Object> pythonConfig,
      Long apiConnectionId) {
    this(
        name,
        description,
        scriptType,
        scriptContent,
        outputDatasetId,
        inputDatasetIds,
        dependsOnStepNames,
        loadStrategy,
        apiConfig,
        aiConfig,
        pythonConfig,
        apiConnectionId,
        null);
  }

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
        null,
        null,
        null);
  }
}
