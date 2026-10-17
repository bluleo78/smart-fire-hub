import { describe, expect, it } from 'vitest';
import { formatKst } from './kst-time.js';

/**
 * formatKst 단위 테스트 (WD-68).
 * 약한 모델이 ISO(UTC) 시각을 KST 로 직접 옮기다 월을 틀렸다 — 도구가 병기할 KST 문자열이
 * 호스트 시간대와 무관하게 정확한지, 특히 UTC 기준 날짜와 KST 기준 날짜가 갈리는 경계를 고정한다.
 */
describe('formatKst', () => {
  it('인시던트 실측값을 같은 날 KST 로 옮긴다', () => {
    expect(formatKst(new Date('2026-10-17T09:46:20.968Z'))).toBe('2026-10-17 18:46');
  });

  it('UTC 기준 전날이 KST 기준 다음날이 되는 경계를 넘긴다', () => {
    expect(formatKst(new Date('2026-10-17T15:30:00.000Z'))).toBe('2026-10-18 00:30');
    expect(formatKst(new Date('2026-10-17T14:59:59.999Z'))).toBe('2026-10-17 23:59');
    expect(formatKst(new Date('2026-10-17T15:00:00.000Z'))).toBe('2026-10-18 00:00');
  });

  it('월·연 경계를 함께 넘긴다', () => {
    expect(formatKst(new Date('2026-09-30T15:00:00.000Z'))).toBe('2026-10-01 00:00');
    expect(formatKst(new Date('2026-12-31T15:00:00.000Z'))).toBe('2027-01-01 00:00');
  });
});
