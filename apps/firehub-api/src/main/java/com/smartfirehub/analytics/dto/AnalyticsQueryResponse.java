package com.smartfirehub.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartfirehub.global.util.JsSafeNumbers;
import java.util.List;
import java.util.Map;

/**
 * 분석 쿼리 결과.
 *
 * @param exportAllowed 조회자 기준 내보내기 가능(EXPORT 정책 AND data:export). 애드혹·저장 쿼리 실행 응답에만 싣는다 — 대시보드 공유
 *     캐시에 들어가는 결과는 null(조회자별 값은 ChartDataResponse.exportAllowed, 설계 결정 5).
 * @param runId 애드혹 실행 기록 id(쿼리 결과 내보내기용, V136 analytics_query_run). 그 외 null.
 * @param exactValues 결과 표 전용 원문(WD-105) — 행 인덱스 → {컬럼명: 원문 텍스트}. {@code rows} 의 NUMERIC·BIGINT 는
 *     차트·대시보드가 {@code typeof number} 로 쓰므로 JSON 숫자로 두는데, 그중 브라우저 double 로 깎이는 칸만 원문을 여기 함께 싣는다(판정은
 *     {@link JsSafeNumbers}). 애드혹·저장 쿼리 실행 응답(웹 결과 표 경로)에만 채우고, 차트 데이터·대시보드 경로는 null. 비었으면 JSON 에서
 *     빠진다(응답 크기 유지).
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
    String runId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<Integer, Map<String, String>> exactValues) {

  /** 기존 호출부 호환 — 내보내기 정보·원문 없음. */
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
        null,
        null);
  }

  /** 내보내기 정보를 채운 사본 — 결과 본문·원문은 그대로 두고 플래그·실행 기록 id 만 바꾼다. */
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
        runId,
        exactValues);
  }

  /**
   * 결과 표 원문을 채운 사본(WD-105) — rows 는 그대로(숫자 유지) 두고, 브라우저 double 로 깎이는 칸만 원문을 {@code exactValues} 에
   * 담는다. 웹 결과 표가 쓰는 애드혹·저장 쿼리 실행 응답에서만 부른다.
   */
  public AnalyticsQueryResponse withExactValues() {
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
        runId,
        JsSafeNumbers.exactValues(rows));
  }
}
