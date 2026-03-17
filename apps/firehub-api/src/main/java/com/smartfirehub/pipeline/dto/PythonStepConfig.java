package com.smartfirehub.pipeline.dto;

import java.util.List;

public record PythonStepConfig(List<OutputColumn> outputColumns) {
  public record OutputColumn(String name, String type) {}
}
