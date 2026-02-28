package com.smartfirehub.analytics.dto;

import java.util.List;

public record SchemaInfoResponse(List<SchemaInfoResponse.TableInfo> tables) {

  public record TableInfo(
      String tableName,
      String datasetName,
      Long datasetId,
      List<SchemaInfoResponse.ColumnInfo> columns) {}

  public record ColumnInfo(String columnName, String dataType, String displayName) {}
}
