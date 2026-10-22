/**
 * Proactive 메시지 공통 유틸리티
 */

/**
 * 리포트를 받았지만 작업 작성자가 아니라 전문을 열 수 없을 때의 안내 (WD-162).
 * 알림 패널·메시지 카드가 같은 문구를 쓰도록 한곳에 둔다 — 화면마다 복제하면 어긋난다.
 */
export const REPORT_OWNER_ONLY_NOTICE = '이 리포트 전문은 작업을 만든 사람만 열 수 있습니다.';

/**
 * 리포트 조회가 404 일 때의 안내 (WD-162).
 * 서버 404 는 「리포트가 없음」과 「작성자가 아님」을 구분해 주지 않으므로(존재 여부를 숨김) 두 경우를 함께 말한다.
 */
export const REPORT_NOT_FOUND_NOTICE =
  '리포트를 찾을 수 없습니다. 리포트 전문은 작업을 만든 사람만 열 수 있습니다.';

/** 실행 상세 조회가 404 일 때의 안내 (WD-162) — 리포트 안내와 같은 이유로 두 경우를 함께 말한다. */
export const EXECUTION_NOT_FOUND_NOTICE =
  '실행 정보를 찾을 수 없습니다. 실행 결과는 작업을 만든 사람만 열 수 있습니다.';

export interface Section {
  key: string;
  label: string;
  content: string;
  data?: unknown;
}

/**
 * 프로액티브 실행 결과 또는 메시지 content에서 표시할 섹션 목록을 추출한다.
 * - sections 배열이 있으면 그대로 사용 (기존 실행 결과)
 * - summary 문자열이 있으면 단일 섹션으로 반환 (ChatDeliveryChannel의 요약)
 * - rawText가 있으면 단일 섹션으로 반환 (폴백)
 */
export function getSections(content: Record<string, unknown>): Section[] {
  if (Array.isArray(content.sections)) {
    return content.sections as Section[];
  }
  if (typeof content.summary === 'string' && content.summary) {
    return [{ key: 'summary', label: '요약', content: content.summary }];
  }
  if (typeof content.rawText === 'string') {
    return [{ key: 'content', label: '분석 결과', content: content.rawText }];
  }
  return [];
}
