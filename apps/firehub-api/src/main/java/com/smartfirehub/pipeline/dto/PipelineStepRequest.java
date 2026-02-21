package com.smartfirehub.pipeline.dto;

import java.util.List;

public record PipelineStepRequest(
    String name,
    String description,
    String scriptType,        // "SQL" or "PYTHON"
    String scriptContent,
    Long outputDatasetId,
    List<Long> inputDatasetIds,
    List<String> dependsOnStepNames,  // reference other steps by name
    String loadStrategy
) {
    public PipelineStepRequest(
            String name, String description, String scriptType,
            String scriptContent, Long outputDatasetId,
            List<Long> inputDatasetIds, List<String> dependsOnStepNames) {
        this(name, description, scriptType, scriptContent, outputDatasetId,
             inputDatasetIds, dependsOnStepNames, "REPLACE");
    }
}
