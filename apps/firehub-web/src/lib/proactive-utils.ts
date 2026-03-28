/**
 * Proactive 메시지 공통 유틸리티
 */

export interface Section {
  key: string;
  label: string;
  content: string;
  data?: unknown;
}

export function getSections(content: Record<string, unknown>): Section[] {
  if (Array.isArray(content.sections)) {
    return content.sections as Section[];
  }
  if (typeof content.rawText === 'string') {
    return [{ key: 'content', label: '분석 결과', content: content.rawText }];
  }
  return [];
}
