package com.smartfirehub.pipeline.dto;

import java.util.List;

public record PipelineStepResponse(
    Long id,
    String name,
    String description,
    String scriptType,
    String scriptContent,
    Long outputDatasetId,
    String outputDatasetName,
    List<Long> inputDatasetIds,
    List<String> dependsOnStepNames,
    int stepOrder
) {}
