/**
 * next-run 단위 테스트 — cron 다음 실행 시간 계산/포맷.
 */
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import {
  formatNextRun,
  formatNextRunShort,
  formatNextRunShortWithZone,
  formatNextRunWithZone,
  nextRunZoneLabel,
  timeZoneLabel,
} from './next-run';

describe('formatNextRun', () => {
  it('날짜/요일/시각 포함 문자열 반환', () => {
    const date = new Date('2026-04-11T00:00:00Z');
    const result = formatNextRun(date, 'Asia/Seoul');
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
  });
});

describe('formatNextRunShort', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-11T00:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('24시간 이내의 오늘 시각', () => {
    const date = new Date('2026-04-11T05:00:00Z');
    const result = formatNextRunShort(date, 'Asia/Seoul');
    expect(typeof result).toBe('string');
    expect(result).toMatch(/오늘|내일/);
  });

  it('24시간 초과는 월일+시간', () => {
    const date = new Date('2026-04-15T05:00:00Z');
    const result = formatNextRunShort(date, 'Asia/Seoul');
    expect(typeof result).toBe('string');
    expect(result).not.toMatch(/오늘|내일/);
  });
});

describe('formatNextRunShort — 지난 시각 (WD-1099)', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    // KST 2026-04-11 15:00
    vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('오늘 이미 지난 시각은 「내일」이 아니라 「오늘」', () => {
    expect(formatNextRunShort(new Date('2026-04-11T00:00:00Z'), 'Asia/Seoul')).toBe('오늘 09:00');
  });
  it('어제 시각은 「내일」이 아니라 날짜', () => {
    expect(formatNextRunShort(new Date('2026-04-10T00:00:00Z'), 'Asia/Seoul')).toBe('4월 10일 09:00');
  });
  it('며칠 지난 시각도 날짜', () => {
    expect(formatNextRunShort(new Date('2026-04-08T00:00:00Z'), 'Asia/Seoul')).toBe('4월 8일 09:00');
  });
  it('미래 회귀: 오늘·내일·그 이후', () => {
    expect(formatNextRunShort(new Date('2026-04-11T12:00:00Z'), 'Asia/Seoul')).toBe('오늘 21:00');
    expect(formatNextRunShort(new Date('2026-04-12T00:00:00Z'), 'Asia/Seoul')).toBe('내일 09:00');
    expect(formatNextRunShort(new Date('2026-04-14T00:00:00Z'), 'Asia/Seoul')).toBe('4월 14일 09:00');
  });
  it('날짜 비교는 UTC 가 아니라 그 시간대 기준', () => {
    // KST 4/12 01:00 — 다음 실행 KST 4/12 00:30 (UTC 로는 4/11)
    vi.setSystemTime(new Date('2026-04-11T16:00:00Z'));
    try {
      expect(formatNextRunShort(new Date('2026-04-11T15:30:00Z'), 'Asia/Seoul')).toBe('오늘 00:30');
    } finally {
      // 단언이 실패해도 시계를 describe 의 기준(KST 4/11 15:00)으로 되돌려 다음 시험에 새지 않게 한다 (WD-1099)
      vi.setSystemTime(new Date('2026-04-11T06:00:00Z'));
    }
  });
  it('앞뒤 공백이 붙은 시간대는 다듬어서 Asia/Seoul 로 본다', () => {
    // 공백을 그대로 Intl 에 넘기면 RangeError 로 브라우저 시간대가 보이므로, 다듬은 값으로 표기해야 한다 (WD-1099)
    const date = new Date('2026-04-14T00:00:00Z');
    expect(formatNextRunShortWithZone(date, ' Asia/Seoul ')).toBe('4월 14일 09:00 KST');
  });
  it('시간대가 비어 있으면 Asia/Seoul 로 본다', () => {
    const date = new Date('2026-04-14T00:00:00Z');
    expect(formatNextRunShortWithZone(date, null)).toBe('4월 14일 09:00 KST');
    expect(formatNextRunShortWithZone(date, '')).toBe('4월 14일 09:00 KST');
    expect(formatNextRunWithZone(new Date('2026-11-25T00:00:00Z'), null)).toContain('09:00 KST');
  });
});

describe('timeZoneLabel (WD-385)', () => {
  it('서머타임 지역은 그 날짜의 약어를 준다', () => {
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'America/New_York')).toBe('EDT');
    expect(timeZoneLabel(new Date(Date.UTC(2026, 11, 29, 5, 30)), 'America/New_York')).toBe('EST');
  });
  it('오프셋만 주는 지역은 고정 약어 표의 약어를 쓴다', () => {
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'Asia/Seoul')).toBe('KST');
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'UTC')).toBe('UTC');
  });
  it('표에 없는 지역은 UTC 오프셋으로 보인다', () => {
    expect(timeZoneLabel(new Date(Date.UTC(2026, 9, 29, 5, 30)), 'Asia/Kolkata')).toBe('UTC+5:30');
  });
});

describe('시간대 이름표를 붙인 다음 실행 (WD-1077)', () => {
  const winter = new Date('2026-11-25T14:00:00Z');
  const summer = new Date('2026-07-15T13:00:00Z');

  it('서울은 KST', () => {
    expect(nextRunZoneLabel(new Date('2026-11-25T00:00:00Z'), 'Asia/Seoul')).toBe('KST');
    expect(formatNextRunWithZone(new Date('2026-11-25T00:00:00Z'), 'Asia/Seoul')).toContain('09:00 KST');
  });
  it('뉴욕은 겨울 EST, 여름 EDT', () => {
    expect(formatNextRunWithZone(winter, 'America/New_York')).toContain('09:00 EST');
    expect(formatNextRunWithZone(summer, 'America/New_York')).toContain('09:00 EDT');
  });
  it('표에 없는 Asia/Kolkata 는 UTC+5:30', () => {
    expect(nextRunZoneLabel(winter, 'Asia/Kolkata')).toBe('UTC+5:30');
    expect(formatNextRunWithZone(winter, 'Asia/Kolkata')).toContain('UTC+5:30');
    expect(formatNextRunShortWithZone(winter, 'Asia/Kolkata')).toContain('UTC+5:30');
  });
  it('Intl 이 모르는 시간대(UTC+9)여도 던지지 않고 비어 있지 않은 문자열을 준다', () => {
    expect(() => formatNextRunWithZone(winter, 'UTC+9')).not.toThrow();
    expect(formatNextRunWithZone(winter, 'UTC+9').length).toBeGreaterThan(0);
    expect(() => formatNextRunShortWithZone(winter, 'UTC+9')).not.toThrow();
    expect(formatNextRunShortWithZone(winter, 'UTC+9').length).toBeGreaterThan(0);
  });
});

describe('formatNextRunShort — 올해가 아닌 날짜는 연도를 붙인다 (WD-1099)', () => {
  beforeAll(() => {
    vi.useFakeTimers();
    // KST 2027-01-02 15:00
    vi.setSystemTime(new Date('2027-01-02T06:00:00Z'));
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  it('해를 넘긴 낡은 시각은 연도와 함께 보인다', () => {
    expect(formatNextRunShort(new Date('2026-12-30T00:00:00Z'), 'Asia/Seoul')).toBe('2026년 12월 30일 09:00');
  });
  it('올해 날짜는 연도 없이 보인다', () => {
    expect(formatNextRunShort(new Date('2027-01-05T00:00:00Z'), 'Asia/Seoul')).toBe('1월 5일 09:00');
  });
});
