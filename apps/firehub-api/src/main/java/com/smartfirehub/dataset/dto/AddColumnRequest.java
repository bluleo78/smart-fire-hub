package com.smartfirehub.dataset.dto;

public record AddColumnRequest(
    String columnName,
    String displayName,
    String dataType,
    boolean isNullable,
    boolean isIndexed,
    String description
) {}
