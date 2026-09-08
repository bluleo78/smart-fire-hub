import { describe, expect, it } from 'vitest';

import { getPageAfterDelete } from './pagination';

describe('getPageAfterDelete', () => {
  it('첫 페이지(0)에서는 항목이 몇 개 남았든 페이지를 보정하지 않는다', () => {
    expect(getPageAfterDelete(0, 0)).toBe(0);
    expect(getPageAfterDelete(1, 0)).toBe(0);
    expect(getPageAfterDelete(5, 0)).toBe(0);
  });

  it('2페이지 이상에서 삭제 대상이 해당 페이지의 마지막 1건이면 이전 페이지로 보정한다', () => {
    expect(getPageAfterDelete(1, 1)).toBe(0);
    expect(getPageAfterDelete(1, 3)).toBe(2);
  });

  it('현재 페이지에 여전히 다른 항목이 남아있으면 페이지를 보정하지 않는다', () => {
    expect(getPageAfterDelete(2, 1)).toBe(1);
    expect(getPageAfterDelete(10, 4)).toBe(4);
  });
});
