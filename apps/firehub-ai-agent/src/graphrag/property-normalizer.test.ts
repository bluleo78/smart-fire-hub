import { describe, it, expect } from 'vitest';
import { normalizeProperty, normalizePropertyChecked } from './property-normalizer.js';

describe('normalizeProperty number', () => {
  it.each([
    ['1억 2천만원', 120_000_000],
    ['약 1억원', 100_000_000],
    ['3,500만원', 35_000_000],
    ['150000000', 150_000_000],
    ['2억', 200_000_000],
  ])('%s → %d', (raw, expected) => {
    expect(normalizeProperty('number', '원', raw)).toBe(expected);
  });
  it('파싱 불가 → null', () => {
    expect(normalizeProperty('number', '원', '피해 규모 큼')).toBeNull();
  });
});

describe('normalizeProperty date', () => {
  it.each([
    ['2024-03-05', '2024-03-05'],
    ['2024년 3월 5일', '2024-03-05'],
    ['2024.3.5', '2024-03-05'],
  ])('%s → %s', (raw, expected) => {
    expect(normalizeProperty('date', undefined, raw)).toBe(expected);
  });
  it('파싱 불가 → null', () => {
    expect(normalizeProperty('date', undefined, '작년 봄')).toBeNull();
  });
});

describe('normalizeProperty text', () => {
  it('트림', () => { expect(normalizeProperty('text', undefined, '  전소  ')).toBe('전소'); });
  it('빈값 → null', () => { expect(normalizeProperty('text', undefined, '   ')).toBeNull(); });
});

describe('normalizePropertyChecked', () => {
  it('정상 파싱은 status=ok', () => {
    expect(normalizePropertyChecked('number', '원', '약 3천만원')).toEqual({ value: 30000000, status: 'ok' });
    expect(normalizePropertyChecked('date', undefined, '2026년 1월 15일')).toEqual({ value: '2026-01-15', status: 'ok' });
  });

  it('빈 문자열은 status=ok(값 없음 — 검수 대상 아님)', () => {
    expect(normalizePropertyChecked('number', '원', '   ')).toEqual({ value: null, status: 'ok' });
  });

  it('비어있지 않은데 파싱 실패면 status=failed', () => {
    expect(normalizePropertyChecked('number', '원', '수천만원대')).toEqual({ value: null, status: 'failed' });
    expect(normalizePropertyChecked('date', undefined, '작년 겨울')).toEqual({ value: null, status: 'failed' });
  });

  it('기존 normalizeProperty는 값만 반환(하위호환)', () => {
    expect(normalizeProperty('number', '원', '약 3천만원')).toBe(30000000);
    expect(normalizeProperty('number', '원', '수천만원대')).toBeNull();
  });
});
