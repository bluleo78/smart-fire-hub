import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useCloseGeneration } from './useCloseGeneration';

// WD-819: 닫힘 세대 공용 훅 — 제출 뒤 닫혔는지·다시 열렸는지 판정한다.
describe('useCloseGeneration', () => {
  it('닫기 전에는 stale 도 reopened 도 아니다', () => {
    const { result } = renderHook(({ open }) => useCloseGeneration(open), { initialProps: { open: true } });
    const { isStale, isReopened } = result.current.capture();
    expect(isStale()).toBe(false);
    expect(isReopened()).toBe(false);
  });

  it('부모가 open=false 로 닫으면 먼저 잡은 capture 가 stale 이고 reopened 는 아니다', () => {
    const { result, rerender } = renderHook(({ open }) => useCloseGeneration(open), { initialProps: { open: true } });
    const { isStale, isReopened } = result.current.capture();
    rerender({ open: false });
    expect(isStale()).toBe(true);
    expect(isReopened()).toBe(false);
  });

  it('닫았다 다시 열면 isReopened 가 true 다', () => {
    const { result, rerender } = renderHook(({ open }) => useCloseGeneration(open), { initialProps: { open: true } });
    const { isStale, isReopened } = result.current.capture();
    rerender({ open: false });
    rerender({ open: true });
    expect(isStale()).toBe(true);
    expect(isReopened()).toBe(true);
  });

  it('닫은 뒤 새로 잡은 capture 는 stale 이 아니다', () => {
    const { result, rerender } = renderHook(({ open }) => useCloseGeneration(open), { initialProps: { open: true } });
    rerender({ open: false });
    const { isStale } = result.current.capture();
    expect(isStale()).toBe(false);
  });
});
