package com.smartfirehub.dataset.dto;

public record DatasetColumnResponse(
    Long id,
    String columnName,
    String displayName,
    String dataType,
    Integer maxLength,
    boolean isNullable,
    boolean isIndexed,
    String description,
    int columnOrder
) {}
