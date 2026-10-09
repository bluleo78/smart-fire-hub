package com.smartfirehub.analytics.dto;

/**
 * 차트 데이터.
 *
 * @param denied 조회자가 원본 데이터셋을 볼 수 없음(스펙 §4.2 4행). true 면 queryResult 는 빈 결과(columns·rows 빈 목록,
 *     error null) — 웹은 "열람 권한 없음" 상태를 그린다(오류·재시도 아님). 거부 코드·원본 데이터셋 이름/id 는 싣지 않는다(스펙 §2.5 존재 은닉).
 * @param exportAllowed 조회자 기준 차트 데이터 내보내기 가능 — 공유 캐시와 무관하게 매 요청 판정 토큰에서 계산한다(설계 결정 5). queryResult
 *     는 조회자 간 공유 캐시에서 올 수 있으므로 플래그를 그 안에 두지 않는다.
 */
public record ChartDataResponse(
    ChartResponse chart,
    AnalyticsQueryResponse queryResult,
    boolean denied,
    boolean exportAllowed) {

  /** 허용 결과용 호환 생성자 — 기존 호출부(denied 없음)는 허용 응답 + 내보내기 불가(fail-closed)가 된다. */
  public ChartDataResponse(ChartResponse chart, AnalyticsQueryResponse queryResult) {
    this(chart, queryResult, false, false);
  }

  /** denied 지정 호환 생성자 — 내보내기 불가(fail-closed). */
  public ChartDataResponse(
      ChartResponse chart, AnalyticsQueryResponse queryResult, boolean denied) {
    this(chart, queryResult, denied, false);
  }
}
