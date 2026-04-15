package com.smartfirehub.pipeline.service.executor;

import java.util.List;
import java.util.Map;

/**
 * API_CALL 스텝 실행 설정.
 *
 * <p>URL 결정 우선순위 (Phase 9 리디자인):
 * <ol>
 *   <li>{@code apiConnectionId}가 설정된 경우: {@code connection.baseUrl + path}
 *   <li>{@code customUrl}이 설정된 경우: customUrl 그대로 사용
 *   <li>하위 호환: {@code url} 필드 (deprecated, 이전 설정 호환용)
 * </ol>
 */
public record ApiCallConfig(
    /* Phase 9 이후에는 customUrl 또는 apiConnectionId+path 사용. 하위 호환용으로 유지. */
    String url,
    String method,
    Map<String, String> headers,
    Map<String, String> queryParams,
    String body,
    String responseFormat,
    String dataPath,
    List<FieldMapping> fieldMappings,
    String sourceTimezone,
    PaginationConfig pagination,
    RetryConfig retry,
    Integer timeoutMs,
    Integer maxDurationMs,
    Integer maxResponseSizeMb,
    Map<String, String> inlineAuth,
    /** Phase 9: apiConnectionId 설정 시 baseUrl + path로 최종 URL 구성. */
    Long apiConnectionId,
    /** Phase 9: apiConnectionId 설정 시 필수. connection.baseUrl에 붙는 경로. */
    String path,
    /** Phase 9: apiConnectionId 없이 호출할 때 사용하는 전체 URL. */
    String customUrl) {
  public record FieldMapping(
      String sourceField,
      String targetColumn,
      String dataType,
      String dateFormat,
      String numberFormat,
      String sourceTimezone) {}

  public record PaginationConfig(
      String type, Integer pageSize, String offsetParam, String limitParam, String totalPath) {}

  public record RetryConfig(Integer maxRetries, Integer initialBackoffMs, Integer maxBackoffMs) {}
}
