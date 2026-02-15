package com.smartfirehub.dataset.dto;

public record UpdateColumnRequest(
    String columnName,
    String displayName,
    String dataType,
    Integer maxLength,
    Boolean isNullable,
    Boolean isIndexed,
    String description
) {}
