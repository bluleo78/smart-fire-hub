import { describe, expect, it } from 'vitest';

import { resolveTabParam } from './tab-param';

describe('resolveTabParam', () => {
  const tabs = ['list', 'templates'] as const;

  it('허용 목록에 있는 값은 그대로 돌려준다', () => {
    expect(resolveTabParam('templates', tabs, 'list')).toBe('templates');
  });

  it.each([null, '', 'zzz', 'LIST'])('%j 는 기본 탭으로 되돌린다', (value) => {
    expect(resolveTabParam(value, tabs, 'list')).toBe('list');
  });
});
