import { describe, it, expect, afterEach } from 'vitest';
import { numEnv } from './constants.js';

describe('numEnv', () => {
  afterEach(() => {
    delete process.env.FH_TEST_N;
  });
  it('유효한 양수 문자열 → 숫자', () => {
    process.env.FH_TEST_N = '12';
    expect(numEnv('FH_TEST_N', 5)).toBe(12);
  });
  it('미설정 → 기본값', () => {
    delete process.env.FH_TEST_N;
    expect(numEnv('FH_TEST_N', 5)).toBe(5);
  });
  it('숫자가 아니면 → 기본값', () => {
    process.env.FH_TEST_N = 'abc';
    expect(numEnv('FH_TEST_N', 5)).toBe(5);
  });
  it('0/음수 → 기본값', () => {
    process.env.FH_TEST_N = '0';
    expect(numEnv('FH_TEST_N', 5)).toBe(5);
    process.env.FH_TEST_N = '-3';
    expect(numEnv('FH_TEST_N', 5)).toBe(5);
  });
});
