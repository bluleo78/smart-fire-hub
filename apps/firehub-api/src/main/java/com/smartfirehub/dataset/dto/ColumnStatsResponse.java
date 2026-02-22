package com.smartfirehub.dataset.dto;

import java.util.List;

public record ColumnStatsResponse(
    String columnName,
    String dataType,
    long totalCount,
    long nullCount,
    double nullPercent,
    long distinctCount,
    String minValue,
    String maxValue,
    Double avgValue,
    List<ValueCount> topValues,
    boolean sampled) {
  public record ValueCount(String value, long count) {}
}
