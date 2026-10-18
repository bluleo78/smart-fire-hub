import { DEFAULT_TRUNCATE_LENGTH } from './constants.js';

export function truncate(text: string, maxLen = DEFAULT_TRUNCATE_LENGTH): string {
  if (text.length <= maxLen) return text;
  return text.slice(0, maxLen) + '...';
}

export function timestamp(): string {
  return new Date().toLocaleTimeString('ko-KR', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/** 양의 정수인지 판정하는 타입 가드 — 요청 바디처럼 검증되지 않은 값(대행 사용자 id, 최대 턴 수·응답 토큰 등)에 쓴다. */
export function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}
