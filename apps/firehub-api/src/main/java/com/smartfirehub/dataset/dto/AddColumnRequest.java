package com.smartfirehub.dataset.dto;

public record AddColumnRequest(
    String columnName,
    String displayName,
    String dataType,
    Integer maxLength,
    boolean isNullable,
    boolean isIndexed,
    String description,
    boolean isPrimaryKey
) {
    public AddColumnRequest(
            String columnName, String displayName, String dataType,
            Integer maxLength, boolean isNullable, boolean isIndexed,
            String description) {
        this(columnName, displayName, dataType, maxLength, isNullable, isIndexed, description, false);
    }
}
