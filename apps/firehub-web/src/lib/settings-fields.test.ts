import { describe, expect, it } from 'vitest';

import type { ResolvedSettingResponse } from '../types/settings';
import { indexSettingsByKey } from './settings-fields';

// 응답 1건을 만드는 헬퍼 — 판정에 쓰이는 필드만 인자로 받는다.
function setting(
  key: string,
  overrides: Partial<ResolvedSettingResponse> = {},
): ResolvedSettingResponse {
  return {
    key,
    value: '1.0',
    description: null,
    updatedAt: null,
    overridden: false,
    tenantEditable: true,
    ...overrides,
  };
}

describe('indexSettingsByKey', () => {
  it('키로 조회할 수 있게 인덱싱한다', () => {
    const byKey = indexSettingsByKey([setting('ai.model'), setting('ai.temperature')]);
    expect(Object.keys(byKey)).toEqual(['ai.model', 'ai.temperature']);
    expect(byKey['ai.model'].key).toBe('ai.model');
  });

  it('빈 목록은 빈 객체가 된다 — 조회 실패 시에도 안전하게 undefined 로 폴백한다', () => {
    expect(indexSettingsByKey([])['ai.model']).toBeUndefined();
  });
});
