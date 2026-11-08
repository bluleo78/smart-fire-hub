/**
 * useErrorFieldFocus 단위 테스트(WD-633) — 저장 실패 포커스.
 * 칸이 있으면 바로, 나중에 그려지면 그때 포커스하고, 사용자 조작·제한 시간이면 요청만 지우며 포커스를 빼앗지 않는다.
 */
import { act, fireEvent, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ERROR_FIELD_FOCUS_TIMEOUT_MS, useErrorFieldFocus } from './useErrorFieldFocus';

/** 훅을 쓰는 최소 컴포넌트 — showField 가 true 일 때만 오류 칸을 그린다(새 함수를 매번 넘겨도 된다) */
function Harness({ focusField, onDone, showField }: { focusField: string | null; onDone: () => void; showField: boolean }) {
  const rootRef = useErrorFieldFocus<HTMLDivElement>(focusField, () => onDone());
  return (
    <div ref={rootRef}>
      <input aria-label="다른 칸" />
      {showField && <input aria-label="오류 칸" data-error-field="apiConfig.path apiConfig.other" />}
    </div>
  );
}

describe('useErrorFieldFocus', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('칸이 있으면 바로 포커스하고 요청을 지운다 — data-error-field 의 여러 키 중 하나만 맞아도 된다', () => {
    const onDone = vi.fn();
    const { getByLabelText } = render(<Harness focusField="apiConfig.path" onDone={onDone} showField />);
    expect(document.activeElement).toBe(getByLabelText('오류 칸'));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('칸이 나중에 그려지면 그때 포커스한다', async () => {
    const onDone = vi.fn();
    const { getByLabelText, rerender } = render(
      <Harness focusField="apiConfig.path" onDone={onDone} showField={false} />,
    );
    expect(onDone).not.toHaveBeenCalled();
    rerender(<Harness focusField="apiConfig.path" onDone={onDone} showField />);
    // MutationObserver 콜백은 마이크로태스크로 돈다
    await act(async () => {
      await Promise.resolve();
    });
    expect(document.activeElement).toBe(getByLabelText('오류 칸'));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('제한 시간 안에 칸이 나타나지 않으면 요청만 지우고, 그 뒤에 칸이 생겨도 포커스를 빼앗지 않는다', async () => {
    const onDone = vi.fn();
    const { getByLabelText, rerender } = render(
      <Harness focusField="apiConfig.path" onDone={onDone} showField={false} />,
    );
    getByLabelText('다른 칸').focus();
    act(() => {
      vi.advanceTimersByTime(ERROR_FIELD_FOCUS_TIMEOUT_MS);
    });
    expect(onDone).toHaveBeenCalledTimes(1);

    // 부모가 요청을 지우기 전에 칸이 그려져도(관찰이 끊겼으므로) 포커스는 그대로다
    rerender(<Harness focusField="apiConfig.path" onDone={onDone} showField />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(document.activeElement).toBe(getByLabelText('다른 칸'));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('기다리는 동안 사용자가 다른 곳을 누르면 포기하고, 그 뒤에 칸이 생겨도 포커스를 빼앗지 않는다', async () => {
    const onDone = vi.fn();
    const { getByLabelText, rerender } = render(
      <Harness focusField="apiConfig.path" onDone={onDone} showField={false} />,
    );
    const other = getByLabelText('다른 칸');
    fireEvent.pointerDown(other);
    other.focus();
    expect(onDone).toHaveBeenCalledTimes(1);

    rerender(<Harness focusField="apiConfig.path" onDone={onDone} showField />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(document.activeElement).toBe(other);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('요청이 지워지면 기다림을 멈춘다 — 제한 시간이 지나도 다시 부르지 않는다', () => {
    const onDone = vi.fn();
    const { rerender } = render(<Harness focusField="apiConfig.path" onDone={onDone} showField={false} />);
    rerender(<Harness focusField={null} onDone={onDone} showField={false} />);
    act(() => {
      vi.advanceTimersByTime(ERROR_FIELD_FOCUS_TIMEOUT_MS * 2);
    });
    expect(onDone).not.toHaveBeenCalled();
  });
});
