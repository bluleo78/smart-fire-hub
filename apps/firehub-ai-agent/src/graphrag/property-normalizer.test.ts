import { describe, it, expect } from 'vitest';
import { normalizeProperty } from './property-normalizer.js';

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
