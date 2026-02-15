package com.smartfirehub.dataimport.dto;

public record ColumnMappingDto(
    String fileColumn,
    String datasetColumn,
    String matchType,
    double confidence
) {}
