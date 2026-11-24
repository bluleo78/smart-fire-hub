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
