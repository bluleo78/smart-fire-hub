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
  /**
   * 미리보기용 필드 매핑 한 줄. dateFormat·sourceTimezone 은 선택(없으면 null — 옛 클라이언트 호환)이며, 실행과 같은 형식·시간대 검사를
   * 미리보기에서도 하려고 받는다(WD-947).
   */
  public record FieldMappingPreview(
      String sourceField,
      String targetColumn,
      String dataType,
      String dateFormat,
      String sourceTimezone) {}
}
