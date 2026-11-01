export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * cron 미리보기 응답 (WD-391) — 서버가 스케줄러와 같은 서머타임 규칙으로 계산한 다음 발화 시각들.
 * `nextFireTimes` 는 UTC ISO-8601(`...Z`) 문자열이라 `parseUtcDate` 로 읽는다.
 */
export interface CronPreviewResponse {
  /** 정규화된(6필드) cron 식 */
  cron: string;
  /** 계산에 쓴 시간대(IANA) */
  timezone: string;
  /** 다음 발화 시각들(UTC). 더 이상 발화하지 않는 식이면 빈 배열 */
  nextFireTimes: string[];
}

/** cron 미리보기 요청 파라미터 */
export interface CronPreviewParams {
  cron: string;
  timezone: string;
  count?: number;
}
