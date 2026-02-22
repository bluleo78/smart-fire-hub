package com.smartfirehub.pipeline.dto;

import java.util.List;
import java.util.Map;

public record ApiCallPreviewRequest(
    String url,
    String method,
    Map<String, String> headers,
    Map<String, String> queryParams,
    String body,
    String dataPath,
    List<FieldMappingPreview> fieldMappings,
    Long apiConnectionId,
    Map<String, String> inlineAuth,
    Integer timeoutMs) {
  public record FieldMappingPreview(String sourceField, String targetColumn, String dataType) {}
}
