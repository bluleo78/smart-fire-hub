/**
 * SearchInput 단위 테스트 (WD-979)
 *
 * debounceMs 를 쓰는 검색창에 한글을 이어 치면, 조합 중 앞 글자의 늦춘 onChange 가 불리고
 * 조합 중 바뀐 외부 value 가 lastExternal 에 반영되지 않아 compositionend 뒤 칸이 옛 값으로 덮였다.
 * 부모 상태를 흉내 내는 하네스로 두 원인을 각각 고정한다.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SearchInput } from './search-input';

/** 부모 상태 흉내 — onChange 로 value 를 갱신하고 호출 기록을 남긴다. 버튼으로 외부 값 변경도 흉내 낸다. */
function Harness({ debounceMs, calls, externalValue }: { debounceMs?: number; calls: string[]; externalValue?: string }) {
  const [value, setValue] = useState('');
  return (
    <div>
      <SearchInput
        value={value}
        debounceMs={debounceMs}
        onChange={(v) => {
          calls.push(v);
          setValue(v);
        }}
      />
      <button onClick={() => setValue(externalValue ?? '')}>외부변경</button>
    </div>
  );
}

const setup = (debounceMs: number | undefined = 300, externalValue?: string) => {
  const calls: string[] = [];
  render(<Harness debounceMs={debounceMs} calls={calls} externalValue={externalValue} />);
  return { calls, input: screen.getByRole('textbox') as HTMLInputElement };
};
const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms));

describe('SearchInput', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('(WD-979) 한글을 이어 치면 조합 중 앞 글자 타이머가 덮지 않아 「가나다」가 남는다', () => {
    const { calls, input } = setup();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '가' } });
    fireEvent.compositionEnd(input);
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '가나' } });
    advance(400);
    fireEvent.compositionEnd(input);
    fireEvent.change(input, { target: { value: '가나다' } });
    advance(400);
    expect(input.value).toBe('가나다');
    expect(calls[calls.length - 1]).toBe('가나다');
    expect(calls).not.toContain('가');
  });

  it('(WD-979) 조합 중 부모가 옛 값을 돌려줘도 조합이 끝난 글자를 유지한다', () => {
    const { calls, input } = setup(300, '가');
    fireEvent.change(input, { target: { value: '가' } });
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '가나' } });
    fireEvent.click(screen.getByText('외부변경'));
    fireEvent.compositionEnd(input);
    // 조합이 끝난 직후(늦춘 onChange 가 불리기 전)에 이미 옛 값으로 덮이지 않아야 한다
    expect(input.value).toBe('가나');
    advance(400);
    expect(input.value).toBe('가나');
    expect(calls[calls.length - 1]).toBe('가나');
  });

  it('조합 중에는 onChange 가 불리지 않는다', () => {
    const { calls, input } = setup();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: 'ㄱ' } });
    fireEvent.change(input, { target: { value: '가' } });
    advance(1000);
    expect(calls).toEqual([]);
  });

  it('영문은 debounce 뒤 마지막 값으로 한 번', () => {
    const { calls, input } = setup();
    for (const v of ['a', 'ab', 'abc']) {
      fireEvent.change(input, { target: { value: v } });
      advance(100);
    }
    advance(300);
    expect(calls).toEqual(['abc']);
  });

  it('지우기는 즉시 빈 값', () => {
    const { calls, input } = setup();
    fireEvent.change(input, { target: { value: 'ab' } });
    fireEvent.click(screen.getByLabelText('검색어 지우기'));
    expect(calls).toEqual(['']);
    advance(500);
    expect(calls).toEqual(['']);
    expect(input.value).toBe('');
  });

  it('부모 초기화는 늦춘 입력을 취소', () => {
    const { calls, input } = setup(300, 'x');
    fireEvent.change(input, { target: { value: 'ab' } });
    fireEvent.click(screen.getByText('외부변경'));
    advance(500);
    expect(calls).toEqual([]);
    expect(input.value).toBe('x');
  });
});
