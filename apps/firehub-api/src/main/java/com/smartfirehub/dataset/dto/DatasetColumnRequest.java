package com.smartfirehub.dataset.dto;

public record DatasetColumnRequest(
    String columnName,
    String displayName,
    String dataType,
    Integer maxLength,
    boolean isNullable,
    boolean isIndexed,
    String description,
    boolean isPrimaryKey) {
  public DatasetColumnRequest(
      String columnName,
      String displayName,
      String dataType,
      Integer maxLength,
      boolean isNullable,
      boolean isIndexed,
      String description) {
    this(columnName, displayName, dataType, maxLength, isNullable, isIndexed, description, false);
  }
}
