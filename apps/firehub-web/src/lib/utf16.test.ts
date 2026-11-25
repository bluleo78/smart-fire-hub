/**
 * truncateUtf16 단위 테스트 (WD-1143)
 * UTF-16 코드 단위 기준 자르기 규칙과 서로게이트 쌍 보호를 고정한다.
 */
import { describe, expect, it } from 'vitest';

import { truncateUtf16 } from './utf16';

describe('truncateUtf16 (WD-1143)', () => {
  it('상한 이내면 그대로 돌려준다', () => {
    expect(truncateUtf16('abc', 5)).toBe('abc');
  });

  it('상한을 넘으면 코드 단위 기준으로 자른다', () => {
    expect(truncateUtf16('abcdef', 3)).toBe('abc');
  });

  it('끝이 서로게이트 쌍의 앞 반쪽에서 끊기면 그 반쪽을 버린다', () => {
    expect(truncateUtf16('a😀', 2)).toBe('a');
  });

  it('쌍이 상한 안에 온전히 들어가면 자르지 않는다', () => {
    expect(truncateUtf16('a😀', 3)).toBe('a😀');
  });

  it('상한이 1이고 이모지만 있으면 빈 문자열이 된다', () => {
    expect(truncateUtf16('😀', 1)).toBe('');
  });

  it('상한이 0이면 빈 문자열이 된다', () => {
    expect(truncateUtf16('abc', 0)).toBe('');
  });
});
