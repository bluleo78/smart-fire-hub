package com.smartfirehub.analytics.dto;

import java.util.List;
import java.util.Map;

/**
 * 분석 쿼리 결과.
 *
 * @param exportAllowed 조회자 기준 내보내기 가능(EXPORT 정책 AND data:export). 애드혹·저장 쿼리 실행 응답에만 싣는다 — 대시보드 공유
 *     캐시에 들어가는 결과는 null(조회자별 값은 ChartDataResponse.exportAllowed, 설계 결정 5).
 * @param runId 애드혹 실행 기록 id(쿼리 결과 내보내기용, V137 analytics_query_run). 그 외 null.
 */
public record AnalyticsQueryResponse(
    String queryType,
    List<String> columns,
    List<Map<String, Object>> rows,
    int affectedRows,
    long executionTimeMs,
    int totalRows,
    boolean truncated,
    String error,
    Boolean exportAllowed,
    String runId) {

  /** 기존 호출부 호환 — 내보내기 정보 없음. */
  public AnalyticsQueryResponse(
      String queryType,
      List<String> columns,
      List<Map<String, Object>> rows,
      int affectedRows,
      long executionTimeMs,
      int totalRows,
      boolean truncated,
      String error) {
    this(
        queryType,
        columns,
        rows,
        affectedRows,
        executionTimeMs,
        totalRows,
        truncated,
        error,
        null,
        null);
  }

  /** 내보내기 정보를 채운 사본 — 결과 본문은 그대로 두고 플래그·실행 기록 id 만 바꾼다. */
  public AnalyticsQueryResponse withExportInfo(Boolean exportAllowed, String runId) {
    return new AnalyticsQueryResponse(
        queryType,
        columns,
        rows,
        affectedRows,
        executionTimeMs,
        totalRows,
        truncated,
        error,
        exportAllowed,
        runId);
  }
}
