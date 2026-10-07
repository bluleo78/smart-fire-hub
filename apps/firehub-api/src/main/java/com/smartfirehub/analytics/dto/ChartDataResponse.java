package com.smartfirehub.analytics.dto;

/**
 * 차트 데이터.
 *
 * @param denied 조회자가 원본 데이터셋을 볼 수 없음(스펙 §4.2 4행). true 면 queryResult 는 빈 결과(columns·rows 빈 목록,
 *     error null) — 웹은 "열람 권한 없음" 상태를 그린다(오류·재시도 아님). 거부 코드·원본 데이터셋 이름/id 는 싣지 않는다(스펙 §2.5 존재 은닉).
 */
public record ChartDataResponse(
    ChartResponse chart, AnalyticsQueryResponse queryResult, boolean denied) {

  /** 허용 결과용 호환 생성자 — 기존 호출부(denied 없음)는 그대로 허용 응답이 된다. */
  public ChartDataResponse(ChartResponse chart, AnalyticsQueryResponse queryResult) {
    this(chart, queryResult, false);
  }
}
