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
