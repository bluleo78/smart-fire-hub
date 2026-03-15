package com.smartfirehub.pipeline.dto;

import java.util.List;

public record AiClassifyConfig(
    String prompt,
    List<OutputColumn> outputColumns,
    List<String> inputColumns,
    Integer batchSize,
    String onError) {
  public record OutputColumn(String name, String type) {}
}
