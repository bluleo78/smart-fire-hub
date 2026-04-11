/**
 * next-run 단위 테스트 — cron 다음 실행 시간 계산/포맷.
 */
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { formatNextRun, formatNextRunShort, getNextRunDate } from './next-run';

describe('getNextRunDate', () => {
  it('유효한 cron은 Date 반환', () => {
    const date = getNextRunDate('0 9 * * *', 'Asia/Seoul');
    expect(date).toBeInstanceOf(Date);
  });

  it('잘못된 cron은 null', () => {
    expect(getNextRunDate('not a cron', 'Asia/Seoul')).toBeNull();
  });

  it('잘못된 타임존도 null로 폴백', () => {
    expect(getNextRunDate('0 9 * * *', 'Invalid/Zone')).toBeNull();
  });
});

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
