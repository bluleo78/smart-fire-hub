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
function keyDown(
  el: HTMLElement,
  init: { key: string; keyCode: number; code?: string; isComposing?: boolean; repeat?: boolean },
) {
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
  /** 조합 확정 Enter 로 한 번 실행해 「뒤따르는 Enter 거르기」 표시가 켜진 상태를 만든다. */
  function confirmByEnter(input: HTMLInputElement) {
    fireEvent.compositionStart(input);
    keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    fireEvent.compositionEnd(input);
  }

  // 마지막 열: 다음 Enter 가 실행되면 2, 아직 걸러지면 1.
  it.each([
    ['Process', 229, '', 2],
    ['Unidentified', 229, '', 2],
    ['Process', 229, 'Enter', 2],
    // 아직 떼지 않은 글자 키의 keyup — 거르기를 끝내면 빠른 타이핑에 검색이 두 번 나간다
    ['Process', 229, 'KeyR', 1],
  ])('확정 뒤 keyup(%s/%i, code=%j) 다음 Enter 실행 횟수 합계는 %i', (key, keyCode, code, expected) => {
    const { onEnter, input } = setup();
    confirmByEnter(input);
    expect(onEnter).toHaveBeenCalledTimes(1);
    fireEvent.keyUp(input, { key, keyCode, code });
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(expected);
  });

  it('확정 뒤 Enter 아닌 키를 누르면 표시를 지워 다음 Enter 를 실행한다(keyup 이 오지 않은 경우)', () => {
    const { onEnter, input } = setup();
    confirmByEnter(input);
    keyDown(input, { key: 'a', keyCode: 65 });
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(2);
  });

  it('확정 뒤 새 조합이 시작되면 표시를 지워, 조합 없이 끝난 뒤 Enter 를 실행한다', () => {
    const { onEnter, input } = setup();
    confirmByEnter(input);
    fireEvent.compositionStart(input);
    fireEvent.compositionEnd(input);
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(2);
  });

  it('확정 뒤 Enter 를 누르고 있어도 자동 반복 Enter 로는 다시 실행하지 않는다', () => {
    const { onEnter, input } = setup();
    confirmByEnter(input);
    keyDown(input, { key: 'Enter', keyCode: 13 });
    keyDown(input, { key: 'Enter', keyCode: 13, repeat: true });
    keyDown(input, { key: 'Enter', keyCode: 13, repeat: true });
    expect(onEnter).toHaveBeenCalledTimes(1);
  });

  it('조합 없는 Enter 를 누르고 있어도 처음 한 번만 실행한다', () => {
    const { onEnter, input } = setup();
    keyDown(input, { key: 'Enter', keyCode: 13 });
    keyDown(input, { key: 'Enter', keyCode: 13, repeat: true });
    expect(onEnter).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['Shift', 16],
    ['Meta', 91],
    ['Process', 229],
  ])('확정 뒤 %s keydown 은 거르기를 끝내지 않는다 — 뒤따르는 Enter 로 중복 검색하지 않는다', (key, keyCode) => {
    const { onEnter, input } = setup();
    confirmByEnter(input);
    keyDown(input, { key, keyCode });
    keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onEnter).toHaveBeenCalledTimes(1);
  });

  it('IME 가 조합 중 Enter 를 key Process(code Enter)로 보내도 확정에 한 번 실행한다(Windows Chrome)', () => {
    const { onEnter, input } = setup();
    fireEvent.compositionStart(input);
    keyDown(input, { key: 'Process', keyCode: 229, code: 'Enter', isComposing: true });
    fireEvent.compositionEnd(input);
    expect(onEnter).toHaveBeenCalledTimes(1);
  });
});
