import { describe, expect, it } from 'vitest';

import { formatDateOnly, formatDateTimeMinute, formatDateTimeSecond, localMidnightIso } from './formatters';

/** 소스와 독립된 기대값 계산(의도적 사본) — 소스 구현을 import 하면 같은 버그가 기대값에도 들어간다.
 * 로컬 시각 기대값을 테스트 프로세스의 시간대로 만든다 — 구체 KST 값은 E2E(timezoneId 고정)가 본다. */
function localSecond(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

describe('formatDateTimeSecond (WD-11)', () => {
  it('오프셋 있는 순간을 브라우저 로컬 시각으로 바꾼다 — 같은 순간이면 표기와 무관하게 같다', () => {
    const expected = localSecond(new Date(Date.UTC(2026, 8, 30, 15, 30, 5)));
    expect(formatDateTimeSecond('2026-09-30T15:30:05Z')).toBe(expected);
    expect(formatDateTimeSecond('2026-10-01T00:30:05+09:00')).toBe(expected);
    expect(formatDateTimeSecond('2026-09-30T15:30:05.123456Z')).toBe(expected);
  });
  it('오프셋이 없으면 UTC 로 본다(웹 parseUtcDate 와 같은 규칙)', () => {
    expect(formatDateTimeSecond('2026-09-30T15:30:05')).toBe(formatDateTimeSecond('2026-09-30T15:30:05Z'));
  });
  it('해석할 수 없으면 원문', () => {
    expect(formatDateTimeSecond('not-a-date')).toBe('not-a-date');
  });
});

describe('formatDateOnly / formatDateTimeMinute (WD-11 — 테넌트 생성일)', () => {
  it('오프셋 있는 순간을 브라우저 로컬 날짜·분으로 바꾼다 — 같은 순간이면 표기와 무관하게 같다', () => {
    const full = localSecond(new Date(Date.UTC(2026, 2, 3, 15, 30, 0)));
    expect(formatDateOnly('2026-03-03T15:30:00Z')).toBe(full.slice(0, 10));
    expect(formatDateOnly('2026-03-04T00:30:00+09:00')).toBe(full.slice(0, 10));
    expect(formatDateTimeMinute('2026-03-03T15:30:00.5Z')).toBe(full.slice(0, 16));
    expect(formatDateTimeMinute('2026-03-04T00:30:00+09:00')).toBe(full.slice(0, 16));
  });
  it('해석할 수 없으면 원문', () => {
    expect(formatDateOnly('bad')).toBe('bad');
    expect(formatDateTimeMinute('bad')).toBe('bad');
  });
});

describe('localMidnightIso (WD-11)', () => {
  it('로컬 자정과 다음 날 자정(배타 상한)의 절대 시각', () => {
    expect(localMidnightIso('2026-09-30')).toBe(new Date(2026, 8, 30).toISOString());
    expect(localMidnightIso('2026-09-30', 1)).toBe(new Date(2026, 9, 1).toISOString());
  });
});
