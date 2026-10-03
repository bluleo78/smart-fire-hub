import { describe, expect, it } from 'vitest';

import { formatDateTimeSecond } from './formatters';

describe('formatDateTimeSecond', () => {
  it('타임존 없는 LocalDateTime 을 자르기만 한다(브라우저 시간대로 바꾸지 않는다)', () => {
    expect(formatDateTimeSecond('2026-10-01T09:15:30')).toBe('2026-10-01 09:15:30');
  });
  it('소수 초가 붙어도 초까지만 보인다', () => {
    expect(formatDateTimeSecond('2026-10-01T09:15:30.123456')).toBe('2026-10-01 09:15:30');
  });
});
