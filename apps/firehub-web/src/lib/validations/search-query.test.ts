/**
 * clampToMaxLength 단위 테스트 (WD-1131)
 * 조합 끝 등으로 maxlength 를 넘어 들어온 검색어를 서버 상한 안으로 자르는 규칙을 고정한다.
 */
import { describe, expect, it } from 'vitest';

import { clampToMaxLength } from './search-query';

describe('clampToMaxLength', () => {
  it('maxLength 가 없거나 값이 상한 이내면 그대로 돌려준다', () => {
    expect(clampToMaxLength('가나다')).toBe('가나다');
    expect(clampToMaxLength('가나다', 3)).toBe('가나다');
  });

  it('상한을 넘으면 UTF-16 코드 유닛 기준으로 자른다', () => {
    expect(clampToMaxLength('가'.repeat(1200), 1000)).toBe('가'.repeat(1000));
  });

  it('끝이 서로게이트 쌍의 앞 반쪽에서 끊기면 그 반쪽을 버린다', () => {
    // '😀' 는 코드 유닛 2개 — 상한 1 로 자르면 앞 반쪽만 남으므로 빈 문자열이 된다
    expect(clampToMaxLength('a😀', 2)).toBe('a');
    expect(clampToMaxLength('a😀', 3)).toBe('a😀');
  });
});
