/**
 * Proactive 메시지 공통 유틸리티
 */

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
