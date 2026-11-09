/**
 * AI 채팅 길이 상한 유틸 단위 테스트 (WD-631)
 *
 * 컨텍스트 자르기가 잘림 표시까지 포함해 서버 상한을 넘지 않는지, 서로게이트 쌍을 깨뜨리지 않는지,
 * 상한 이하·빈 값은 그대로 두는지 확인한다.
 */
import { describe, expect, it } from 'vitest';

import {
  CONTEXT_TRUNCATED_MARKER,
  MAX_SCREEN_CONTEXT_LENGTH,
  messageTooLongHint,
  truncateChatContext,
} from './ai-chat-limits';

describe('truncateChatContext', () => {
  it('상한 이하이거나 비어 있으면 그대로 돌려준다', () => {
    expect(truncateChatContext(undefined, 10)).toBeUndefined();
    expect(truncateChatContext('', 10)).toBe('');
    expect(truncateChatContext('a'.repeat(10), 10)).toBe('a'.repeat(10));
  });

  it('상한을 넘으면 잘림 표시를 붙이고, 전체 길이가 정확히 상한이다', () => {
    const out = truncateChatContext('가'.repeat(MAX_SCREEN_CONTEXT_LENGTH + 500), MAX_SCREEN_CONTEXT_LENGTH)!;
    expect(out.length).toBe(MAX_SCREEN_CONTEXT_LENGTH);
    expect(out.endsWith(CONTEXT_TRUNCATED_MARKER)).toBe(true);
    expect(out.startsWith('가가가')).toBe(true);
  });

  it('자르는 자리가 서로게이트 쌍 한가운데면 한 글자를 더 빼 깨진 문자를 남기지 않는다', () => {
    const max = 50;
    const keep = max - CONTEXT_TRUNCATED_MARKER.length;
    // keep-1 위치에 이모지(2 코드 단위)의 앞 절반이 오도록 만든다
    const text = 'a'.repeat(keep - 1) + '😀' + 'b'.repeat(100);
    const out = truncateChatContext(text, max)!;
    expect(out).toBe('a'.repeat(keep - 1) + CONTEXT_TRUNCATED_MARKER);
    expect(out.length).toBeLessThanOrEqual(max);
  });
});

describe('truncateChatContext — 상한이 잘림 표시보다 짧을 때', () => {
  it('어떤 max 에서도 결과 길이가 max 를 넘지 않는다', () => {
    const text = 'x'.repeat(200);
    for (let max = 0; max <= CONTEXT_TRUNCATED_MARKER.length + 5; max++) {
      expect(truncateChatContext(text, max)!.length).toBeLessThanOrEqual(max);
    }
  });

  it('max 가 잘림 표시 길이 이하이면 표시 없이 앞부분만 남긴다(서로게이트 보호 포함)', () => {
    expect(truncateChatContext('abcdefghij', 3)).toBe('abc');
    expect(truncateChatContext('ab😀cdefghij', 3)).toBe('ab');
    expect(truncateChatContext('abc', 0)).toBe('');
  });
});

describe('messageTooLongHint', () => {
  it('서버 문구와 같은 표현에 현재 길이를 천 단위 구분으로 붙인다', () => {
    expect(messageTooLongHint(51234)).toBe('메시지는 50,000자 이하로 입력하세요. (현재 51,234자)');
  });
});
