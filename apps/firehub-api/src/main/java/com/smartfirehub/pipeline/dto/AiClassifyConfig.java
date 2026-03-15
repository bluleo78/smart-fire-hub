package com.smartfirehub.pipeline.dto;

import java.util.List;

public record AiClassifyConfig(
    String sourceColumn,
    String keyColumn,
    List<String> labels,
    String promptTemplate,
    String targetPrefix,
    Integer batchSize,
    Double confidenceThreshold,
    String onLowConfidence,
    String onError) {}
