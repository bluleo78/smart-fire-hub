/**
 * useDebounceValue 단위 테스트 — 값 변경 후 delay 뒤 반영.
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useDebounceValue } from './useDebounceValue';

describe('useDebounceValue', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('초기값을 즉시 반환', () => {
    const { result } = renderHook(() => useDebounceValue('hello', 300));
    expect(result.current).toBe('hello');
  });

  it('값 변경 후 delay 이전엔 이전 값 유지', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounceValue(value, 500),
      { initialProps: { value: 'a' } },
    );
    rerender({ value: 'b' });
    expect(result.current).toBe('a');
    act(() => {
      vi.advanceTimersByTime(499);
    });
    expect(result.current).toBe('a');
  });

  it('delay 경과 후 새 값 반영', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounceValue(value, 500),
      { initialProps: { value: 'a' } },
    );
    rerender({ value: 'b' });
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(result.current).toBe('b');
  });

  it('연속 변경 시 마지막 값만 반영(이전 타이머 취소)', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounceValue(value, 300),
      { initialProps: { value: 'a' } },
    );
    rerender({ value: 'b' });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    rerender({ value: 'c' });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe('a');
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(result.current).toBe('c');
  });

  it('기본 delay(300ms) 동작', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounceValue(value),
      { initialProps: { value: 1 } },
    );
    rerender({ value: 2 });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current).toBe(2);
  });
});
