/**
 * cron-label 단위 테스트 — cron 표현식 → 사람이 읽기 쉬운 라벨.
 */
import { describe, expect, it } from 'vitest';

import { cronToLabel } from './cron-label';

describe('cronToLabel', () => {
  it('알려진 표현식은 한글 라벨로', () => {
    expect(cronToLabel('0 9 * * *')).toBe('매일 오전 9시');
    expect(cronToLabel('0 0 * * *')).toBe('매일 자정');
    expect(cronToLabel('0 9 * * 1-5')).toBe('평일 오전 9시');
    expect(cronToLabel('*/30 * * * *')).toBe('30분마다');
    expect(cronToLabel('0 * * * *')).toBe('매시간');
    expect(cronToLabel('0 0 1 * *')).toBe('매월 1일 자정');
  });

  it('알 수 없는 표현식은 원본 반환', () => {
    expect(cronToLabel('15 4 * * 3')).toBe('15 4 * * 3');
  });
});
