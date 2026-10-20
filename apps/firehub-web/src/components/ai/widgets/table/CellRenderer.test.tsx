/**
 * CellRenderer 단위 테스트 (WD-101)
 *
 * 데이터셋 행 API 는 JS double 로 깎이는 NUMERIC·BIGINT 값을 문자열로 보낸다. AI 위젯 표가 그 문자열을
 * Number() 로 바꾸면 다시 깎이고 소수가 3자리로 잘리므로, 숫자 문자열은 자릿수를 그대로 둔 채 천 단위 쉼표만 넣는지 검증한다.
 * 실제 JS number 값은 기존처럼 toLocaleString('ko-KR') 표시(소수 최대 3자리)를 유지한다.
 */
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CellRenderer } from './CellRenderer';

/** 렌더 결과 텍스트만 꺼낸다 */
function renderText(value: unknown, isNumericColumn?: boolean): string {
  const { container } = render(<CellRenderer value={value} isNumericColumn={isNumericColumn} />);
  return container.textContent ?? '';
}

describe('CellRenderer — 숫자 문자열 정밀도 (WD-101)', () => {
  it('큰 소수 문자열은 소수 6자리까지 그대로 보인다', () => {
    expect(renderText('123456789012.123456')).toBe('123,456,789,012.123456');
  });

  it('2^53 을 넘는 정수 문자열은 마지막 자리까지 그대로 보인다', () => {
    expect(renderText('9007199254740993')).toBe('9,007,199,254,740,993');
  });

  it('음수는 부호를 유지하고, 0 만 남는 음수는 부호 없이 보인다', () => {
    expect(renderText('-1234.000001')).toBe('-1,234.000001');
    expect(renderText('-0')).toBe('0');
  });

  it('isNumericColumn 으로 들어온 숫자 문자열도 같은 정밀도 경로를 탄다', () => {
    expect(renderText('123456789012.123456', true)).toBe('123,456,789,012.123456');
  });

  it('JS number 값은 기존 toLocaleString 표시를 유지한다', () => {
    expect(renderText(1234567)).toBe('1,234,567');
    expect(renderText(5)).toBe('5');
    expect(renderText(1.23456)).toBe((1.23456).toLocaleString('ko-KR'));
  });

  it('평범한 10진 형식이 아닌 숫자 문자열은 기존 Number 경로로 표시한다', () => {
    expect(renderText('1e5')).toBe((1e5).toLocaleString('ko-KR'));
  });

  it('숫자가 아닌 문자열·null 은 숫자 서식을 타지 않는다', () => {
    expect(renderText('가나다')).toBe('가나다');
    expect(renderText(null)).toBe('—');
  });
});
