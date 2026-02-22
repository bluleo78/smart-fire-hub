package com.smartfirehub.dataset.dto;

import java.util.Map;

public record ApiImportRequest(
    String pipelineName,
    String pipelineDescription,
    Map<String, Object> apiConfig,
    Long apiConnectionId,
    String loadStrategy,
    boolean executeImmediately,
    ScheduleConfig schedule) {
  public record ScheduleConfig(String cronExpression, String name, String description) {}
}
