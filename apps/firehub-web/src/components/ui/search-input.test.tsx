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

  it('(WD-979 리뷰 후속) Chromium 순서(change 「가나다」 → compositionEnd)면 compositionend 가 최종 값을 보낸다', () => {
    // Chromium 은 마지막 글자의 input(change) 이 compositionend 보다 먼저 온다 — 조합 중 change 는 보류되므로
    // compositionend 가 칸의 최종 값을 읽어 보내야 마지막 글자가 빠지지 않는다.
    const { calls, input } = setup();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '가나다' } });
    // debounce(300ms)를 넘겨도 조합 중 change 는 보내지 않는다
    advance(400);
    expect(calls).toEqual([]);
    fireEvent.compositionEnd(input);
    advance(300);
    expect(calls).toEqual(['가나다']);
    expect(input.value).toBe('가나다');
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

/**
 * maxLength 자체 강제 (WD-1131)
 * 한글 조합이 끝날 때 브라우저가 maxlength 를 지키지 않고 값을 넘겨도 컴포넌트가 잘라 보낸다.
 */
describe('SearchInput maxLength (WD-1131)', () => {
  const setupMax = (maxLength?: number) => {
    const onChange = vi.fn();
    render(<SearchInput value="" onChange={onChange} maxLength={maxLength} />);
    return { onChange, input: screen.getByRole('textbox') as HTMLInputElement };
  };

  it('조합 끝 값이 maxLength 를 넘으면 잘라 표시하고 잘린 값으로 onChange', () => {
    const { onChange, input } = setupMax(5);
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '가나다라마바사' } });
    fireEvent.compositionEnd(input);
    expect(onChange).toHaveBeenLastCalledWith('가나다라마');
    expect(input.value).toBe('가나다라마');
  });

  it('일반 입력 값이 maxLength 를 넘어도 잘라 보낸다', () => {
    const { onChange, input } = setupMax(3);
    fireEvent.change(input, { target: { value: 'abcdef' } });
    expect(onChange).toHaveBeenLastCalledWith('abc');
  });

  it('maxLength 가 없으면 자르지 않는다', () => {
    const { onChange, input } = setupMax();
    fireEvent.change(input, { target: { value: 'a'.repeat(2000) } });
    expect(onChange.mock.calls[0][0]).toHaveLength(2000);
  });

  it('끝이 서로게이트 쌍에 걸치면 반쪽을 버린다', () => {
    const { onChange, input } = setupMax(3);
    fireEvent.change(input, { target: { value: 'ab😀c' } });
    expect(onChange).toHaveBeenLastCalledWith('ab');
  });
});

describe('SearchInput 바깥 값 상한 (WD-1143)', () => {
  it('처음부터 상한을 넘는 value 는 잘라 표시하고 onChange 를 한 번 부른다', () => {
    const onChange = vi.fn();
    render(<SearchInput value="abcdef" onChange={onChange} maxLength={3} />);
    expect(screen.getByRole('textbox')).toHaveValue('abc');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('abc');
  });

  it('다시 그릴 때 들어온 긴 value 도 자르고 알린다', () => {
    const onChange = vi.fn();
    const { rerender } = render(<SearchInput value="ab" onChange={onChange} maxLength={3} />);
    expect(onChange).not.toHaveBeenCalled();
    rerender(<SearchInput value="xyzw" onChange={onChange} maxLength={3} />);
    expect(screen.getByRole('textbox')).toHaveValue('xyz');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('xyz');
  });

  it('상한 이하 value 는 알리지 않는다', () => {
    const onChange = vi.fn();
    render(<SearchInput value="ab" onChange={onChange} maxLength={3} />);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('서로게이트 쌍을 쪼개지 않고 자른다', () => {
    const onChange = vi.fn();
    render(<SearchInput value={'a😀'} onChange={onChange} maxLength={2} />);
    expect(screen.getByRole('textbox')).toHaveValue('a');
    expect(onChange).toHaveBeenCalledWith('a');
  });

  it('maxLength 가 없으면 그대로 두고 알리지 않는다', () => {
    const onChange = vi.fn();
    render(<SearchInput value="abcdef" onChange={onChange} />);
    expect(screen.getByRole('textbox')).toHaveValue('abcdef');
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('SearchInput 마운트·조합 끝 자르기 (WD-1159)', () => {
  it('상한 이하 값으로 마운트하면 onChange 를 부르지 않는다', () => {
    const onChange = vi.fn();
    render(<SearchInput value="abc" onChange={onChange} maxLength={1000} />);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('상한을 넘는 값으로 마운트하면 잘린 값으로 한 번 부른다', () => {
    const onChange = vi.fn();
    render(<SearchInput value={'a'.repeat(1001)} onChange={onChange} maxLength={1000} />);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toHaveLength(1000);
  });

  it('조합 중 부모가 상한을 넘긴 값을 내려도 조합이 끝나면 잘린 값으로 맞춘다', () => {
    const onChange = vi.fn();
    const { rerender } = render(<SearchInput value="" onChange={onChange} maxLength={1000} />);
    const input = screen.getByRole('textbox') as HTMLInputElement;
    fireEvent.compositionStart(input);
    // 조합 중 부모가 상한을 넘는 값을 내려준다(효과는 조합 중이라 건너뜀)
    const over = 'a'.repeat(1001);
    rerender(<SearchInput value={over} onChange={onChange} maxLength={1000} />);
    // 조합 중 입력된 값은 그대로 칸에 있다
    fireEvent.change(input, { target: { value: over } });
    fireEvent.compositionEnd(input);
    expect(onChange).toHaveBeenCalled();
    expect(onChange.mock.calls.at(-1)![0].length).toBeLessThanOrEqual(1000);
    expect(input.value.length).toBeLessThanOrEqual(1000);
  });
});
