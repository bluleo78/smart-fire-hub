import { describe, expect, it } from 'vitest';

import { isDecimalSyntax, isIntegerSyntax } from './settings-number';

// 서버(SettingsService)가 받는 표기와 같은 집합이어야 한다(#727) — 한쪽만 넓으면 "검증은 통과하고
// 저장은 400" 이 다시 생긴다.
describe('settings-number', () => {
  it('정수 표기는 ASCII 숫자만 받는다', () => {
    for (const ok of ['1', '10', '587', '050', '65535']) expect(isIntegerSyntax(ok)).toBe(true);
    // JS Number() 는 전부 정수로 읽지만 Java Integer.parseInt 는 못 읽거나(587.0·1e1·0x10),
    // 읽더라도 원문 그대로 저장되는(+5) 표기들.
    for (const bad of ['587.0', '5e2', '1e1', '0x10', '+5', '-1', ' 5', '5 ', '', '١٢'])
      expect(isIntegerSyntax(bad)).toBe(false);
  });

  it('소수 표기는 평범한 십진 표기만 받는다', () => {
    for (const ok of ['0', '1', '1.0', '0.75', '0.10']) expect(isDecimalSyntax(ok)).toBe(true);
    for (const bad of ['1e-1', '.5', '1.', '0x0', 'NaN', 'Infinity', '+0.5', '-0.5', '0.5d', ''])
      expect(isDecimalSyntax(bad)).toBe(false);
  });
});
