import { describe, expect, it } from 'vitest';

import { keepPreviousWhenOnlyPageChanged } from './keepPreviousWhenOnlyPageChanged';

/** page 만 다를 때만 이전 데이터를 유지하는 placeholderData 콜백 (WD-932) */
describe('keepPreviousWhenOnlyPageChanged', () => {
  const prev = { content: ['이전'] };
  const q = (params: Record<string, unknown>) => ({ queryKey: ['auditLogs', params] as const });

  it('page 만 다르면 이전 데이터를 유지한다', () => {
    const cb = keepPreviousWhenOnlyPageChanged<typeof prev>({ search: 'a', page: 1, size: 20 });
    expect(cb(prev, q({ search: 'a', page: 0, size: 20 }))).toBe(prev);
  });

  it('undefined 필터는 없는 것과 같게 본다', () => {
    const cb = keepPreviousWhenOnlyPageChanged<typeof prev>({ search: undefined, page: 1 });
    expect(cb(prev, q({ page: 0 }))).toBe(prev);
  });

  it('키 순서만 다르면 같은 조건으로 보고 이전 데이터를 유지한다', () => {
    const cb = keepPreviousWhenOnlyPageChanged<typeof prev>({ size: 20, search: 'a', page: 1 });
    expect(cb(prev, q({ page: 0, search: 'a', size: 20 }))).toBe(prev);
  });

  it('필터가 다르면 이전 데이터를 버린다', () => {
    const cb = keepPreviousWhenOnlyPageChanged<typeof prev>({ search: 'b', page: 0, size: 20 });
    expect(cb(prev, q({ search: 'a', page: 0, size: 20 }))).toBeUndefined();
  });

  it('size 가 다르면 이전 데이터를 버린다', () => {
    const cb = keepPreviousWhenOnlyPageChanged<typeof prev>({ page: 0, size: 50 });
    expect(cb(prev, q({ page: 0, size: 20 }))).toBeUndefined();
  });

  it('이전 쿼리가 없으면 undefined', () => {
    const cb = keepPreviousWhenOnlyPageChanged<typeof prev>({ page: 0 });
    expect(cb(undefined, undefined)).toBeUndefined();
  });

  it('키의 마지막 원소를 params 로 읽어 쪽만 바뀐 것을 가린다 (WD-982)', () => {
    const q3 = (params: Record<string, unknown>) => ({
      queryKey: ['analytics', 'charts', 'list', params] as const,
    });
    const same = keepPreviousWhenOnlyPageChanged<typeof prev>({ page: 1, search: '' });
    expect(same(prev, q3({ page: 0, search: '' }))).toBe(prev);
    const other = keepPreviousWhenOnlyPageChanged<typeof prev>({ page: 1, search: 'x' });
    expect(other(prev, q3({ page: 0, search: '' }))).toBeUndefined();
  });
});
