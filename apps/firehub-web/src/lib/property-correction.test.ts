import { describe, expect, it } from 'vitest';

import { validatePropertyCorrection } from './property-correction';

// #311 — 정정값 형식 검증. 서버(ai-agent coercePropertyValue)와 같은 규칙을 입력 시점에 적용한다.
describe('validatePropertyCorrection', () => {
  it('타입과 무관하게 빈 값·공백만 있는 값은 사유를 돌려준다', () => {
    expect(validatePropertyCorrection('date', '')).toBe('정정값을 입력하세요.');
    expect(validatePropertyCorrection('number', '   ')).toBe('정정값을 입력하세요.');
    expect(validatePropertyCorrection('text', ' ')).toBe('정정값을 입력하세요.');
  });

  it('number는 숫자만 통과시킨다', () => {
    expect(validatePropertyCorrection('number', '30000000')).toBeNull();
    expect(validatePropertyCorrection('number', '삼천만원 정도')).toBe('숫자만 입력할 수 있습니다(예: 30000000).');
  });

  it('date는 YYYY-MM-DD와 관용 표기를 통과시키고 그 외는 형식을 안내한다', () => {
    expect(validatePropertyCorrection('date', '2026-01-05')).toBeNull();
    expect(validatePropertyCorrection('date', '2026.1.5')).toBeNull();
    expect(validatePropertyCorrection('date', '2026년 1월 5일')).toBeNull();
    expect(validatePropertyCorrection('date', '작년겨울')).toBe('YYYY-MM-DD 형식의 날짜를 입력하세요(예: 2026-01-05).');
  });

  it('date는 달력상 존재하지 않는 날짜를 거른다', () => {
    expect(validatePropertyCorrection('date', '2026-02-31')).toBe('존재하지 않는 날짜입니다.');
    expect(validatePropertyCorrection('date', '2026-13-01')).toBe('존재하지 않는 날짜입니다.');
    expect(validatePropertyCorrection('date', '2024-02-29')).toBeNull(); // 윤년은 유효
  });

  it('text는 내용이 있으면 통과하고 1000자를 넘으면 거른다', () => {
    expect(validatePropertyCorrection('text', '전기적 요인')).toBeNull();
    expect(validatePropertyCorrection('text', 'a'.repeat(1001))).toContain('1000자 이하로 입력하세요');
  });
});
