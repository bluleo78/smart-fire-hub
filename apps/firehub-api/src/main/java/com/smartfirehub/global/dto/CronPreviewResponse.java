package com.smartfirehub.global.dto;

import java.util.List;

/**
 * cron 미리보기 응답 (WD-391).
 *
 * @param cron 정규화된(6필드) cron 식 — 스케줄러가 실제로 쓰는 값
 * @param timezone 계산에 쓴 시간대(IANA ID)
 * @param nextFireTimes 다음 발화 시각들. UTC ISO-8601 문자열({@code ...Z}) — 화면은 이 시각을 트리거 시간대로 표시한다. 더 이상
 *     발화하지 않는 식이면 비어 있을 수 있다.
 */
public record CronPreviewResponse(String cron, String timezone, List<String> nextFireTimes) {}
