import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { useImeSafeEnter } from './useImeSafeEnter';

/** 훅을 실제 input 에 붙여 보는 시험용 컴포넌트. */
function Harness({ onEnter }: { onEnter: (v: string) => void }) {
  const ime = useImeSafeEnter(onEnter);
  return (
    <input
      aria-label="검색"
      onKeyDown={ime.onKeyDown}
      onCompositionStart={ime.onCompositionStart}
      onCompositionEnd={ime.onCompositionEnd}
      onBlur={ime.onBlur}
      onKeyUp={ime.onKeyUp}
    />
  );
}

/** jsdom 의 fireEvent 는 isComposing 을 못 실을 수 있어 KeyboardEvent 를 직접 만들어 보낸다. */
function keyDown(el: HTMLElement, init: { key: string; keyCode: number; isComposing?: boolean }) {
  fireEvent(el, new KeyboardEvent('keydown', { ...init, bubbles: true, cancelable: true }));
}

function setup() {
  const onEnter = vi.fn();
  render(<Harness onEnter={onEnter} />);
  const input = screen.getByLabelText('검색') as HTMLInputElement;
  return { onEnter, input };
}

describe('useImeSafeEnter', () => {
  it('Chrome 순서: 조합 중 Enter 뒤 compositionend 에 한 번 실행', () => {
    const { onEnter, input } = setup();
    fireEvent.compositionStart(input);
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    expect(onEnter).not.toHaveBeenCalled();
    input.value = '검색';
    fireEvent.compositionEnd(input);
    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(onEnter).toHaveBeenCalledWith('검색');
  });

  it('Safari 순서: compositionend 뒤 keyCode 229 Enter 에 한 번 실행', () => {
    const { onEnter, input } = setup();
    fireEvent.compositionStart(input);
    input.value = '검색';
    fireEvent.compositionEnd(input);
    expect(onEnter).not.toHaveBeenCalled();
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: false });
    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(onEnter).toHaveBeenCalledWith('검색');
  });

  it('조합 중 isComposing 만 켜진 Enter(ref 없이)도 대기로 본다', () => {
    const { onEnter, input } = setup();
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    expect(onEnter).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    expect(onEnter).toHaveBeenCalledTimes(1);
  });

  it('확정 뒤 keyup 전까지 오는 일반 Enter 는 다른 task 여도 거르고, keyup 뒤 Enter 는 실행', async () => {
    const { onEnter, input } = setup();
    fireEvent.compositionStart(input);
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    fireEvent.compositionEnd(input);
    expect(onEnter).toHaveBeenCalledTimes(1);
    // macOS Chrome 의 뒤따르는 Enter 는 다음 task 로 올 수 있다 — 시간이 지나도 keyup 전이면 거른다.
    await new Promise((r) => setTimeout(r, 20));
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(1);
    fireEvent.keyUp(input, { key: 'Enter', keyCode: 13 });
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(2);
  });

  it('대기 중 blur 면 실행하지 않는다', () => {
    const { onEnter, input } = setup();
    fireEvent.compositionStart(input);
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    fireEvent.blur(input);
    fireEvent.compositionEnd(input);
    expect(onEnter).not.toHaveBeenCalled();
  });

  it('조합 시작 때 낡은 대기를 비운다 — compositionend 없이 다시 시작한 조합 끝에서 실행하지 않는다', () => {
    const { onEnter, input } = setup();
    fireEvent.compositionStart(input);
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    // compositionend 없이 새 조합이 시작됨
    fireEvent.compositionStart(input);
    fireEvent.compositionEnd(input);
    expect(onEnter).not.toHaveBeenCalled();
  });

  it('조합 아닌 일반 Enter 는 바로 실행, 다른 키는 무시', () => {
    const { onEnter, input } = setup();
    input.value = 'abc';
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(onEnter).toHaveBeenCalledWith('abc');
    keyDown(input, { key: 'a', keyCode: 65 });
    expect(onEnter).toHaveBeenCalledTimes(1);
  });
});
