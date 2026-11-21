import { describe, expect, it } from 'vitest';

import { withoutBlankAllowedIps } from './allowedIps';

// WD-882: 편집 창이 불러올 때 allowedIps 공백 원소를 서버(TriggerService.isBlankEntry — Java trim)와 같은 규칙으로 거른다.
describe('withoutBlankAllowedIps', () => {
  it('공백뿐인 원소(빈 문자열·공백·탭)는 빼고 유효 원소는 순서·모양 그대로 둔다', () => {
    expect(withoutBlankAllowedIps({ allowedIps: ['', ' ', '\t', ' 10.0.0.1', '10.0.0.0/8'] })).toEqual({
      allowedIps: [' 10.0.0.1', '10.0.0.0/8'],
    });
  });

  it('Java trim 이 자르지 않는 유니코드 공백(U+3000·U+00A0)은 서버가 제한으로 보므로 남긴다', () => {
    expect(withoutBlankAllowedIps({ allowedIps: ['　', ' '] })).toEqual({ allowedIps: ['　', ' '] });
  });

  it('Java trim 이 자르는 제어 문자뿐인 원소(U+0001)는 뺀다', () => {
    expect(withoutBlankAllowedIps({ allowedIps: ['\u0001'] })).toEqual({ allowedIps: [] });
  });

  it('문자열이 아닌 원소가 있으면 거르지 않는다 — 서버가 모든 호출을 막는 목록을 「제한 없음」으로 바꾸지 않게', () => {
    const config = { allowedIps: ['', null, '10.0.0.0/8'] };
    expect(withoutBlankAllowedIps(config)).toBe(config);
  });

  it('allowedIps 가 없으면(WD-109) 그대로 둔다', () => {
    const config = { other: 1 };
    expect(withoutBlankAllowedIps(config)).toBe(config);
  });
});
