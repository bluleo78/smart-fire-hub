import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { __resetWidgetRenderQueueForTest, useWidgetRenderTurn } from './widget-render-queue';

// WD-1072: 위젯 그리기 차례 큐 — 한 번에 하나씩만 차례를 받는지 검증한다.
// 보이는 탭의 차례 넘김은 이중 rAF — 프레임(16ms)을 두 번 진행하면 다음 위젯이 차례를 받는다
function frame() {
  act(() => {
    vi.advanceTimersByTime(16);
  });
}
function tick() {
  frame();
  frame();
}

/** document.visibilityState 를 스텁한다 */
function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
}

describe('useWidgetRenderTurn', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] });
    setVisibility('visible');
    __resetWidgetRenderQueueForTest();
  });
  afterEach(() => {
    vi.useRealTimers();
    // 스텁을 걷어 인스턴스 속성을 지우고 jsdom 기본(prototype getter)으로 되돌린다
    delete (document as unknown as Record<string, unknown>).visibilityState;
  });

  it('첫 위젯은 바로 차례를 받는다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    tick();
    expect(a.result.current).toBe(true);
  });

  it('두 번째 위젯은 첫 위젯이 반납하고 한 틱 뒤에 차례를 받는다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(() => useWidgetRenderTurn(true));
    expect(a.result.current).toBe(true);
    expect(b.result.current).toBe(false);
    tick();
    expect(b.result.current).toBe(true);
  });

  it('세 위젯이 들어온 순서대로 하나씩 받는다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(() => useWidgetRenderTurn(true));
    const c = renderHook(() => useWidgetRenderTurn(true));
    const count = () => [a, b, c].filter((h) => h.result.current).length;
    expect(count()).toBe(1);
    expect(a.result.current).toBe(true);
    tick();
    expect(count()).toBe(2);
    expect(b.result.current).toBe(true);
    tick();
    expect(count()).toBe(3);
    expect(c.result.current).toBe(true);
  });

  it('ready 가 false 면 줄을 서지 않는다', () => {
    const x = renderHook(() => useWidgetRenderTurn(false));
    const y = renderHook(() => useWidgetRenderTurn(true));
    tick();
    expect(y.result.current).toBe(true);
    expect(x.result.current).toBe(false);
  });

  it('ready 가 false 가 되면 줄에서 빠진다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(({ r }) => useWidgetRenderTurn(r), { initialProps: { r: true } });
    b.rerender({ r: false });
    const c = renderHook(() => useWidgetRenderTurn(true));
    expect(a.result.current).toBe(true);
    tick();
    tick();
    expect(c.result.current).toBe(true);
    expect(b.result.current).toBe(false);
  });

  it('언마운트하면 차례를 반납한다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(() => useWidgetRenderTurn(true));
    a.unmount();
    tick();
    expect(b.result.current).toBe(true);
  });

  it('한 번 받은 차례는 ready 가 false 가 돼도 유지된다', () => {
    const a = renderHook(({ r }) => useWidgetRenderTurn(r), { initialProps: { r: true } });
    tick();
    expect(a.result.current).toBe(true);
    a.rerender({ r: false });
    expect(a.result.current).toBe(true);
  });

  it('보이는 탭에서는 반납 뒤 rAF 한 번으로는 부족하고 두 번째에 다음 위젯이 차례를 받는다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(() => useWidgetRenderTurn(true));
    a.unmount();
    frame();
    expect(b.result.current).toBe(false);
    frame();
    expect(b.result.current).toBe(true);
  });

  it('숨은 탭에서는 타이머·rAF 진행 없이 다음 위젯이 바로 차례를 받는다', () => {
    setVisibility('hidden');
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(() => useWidgetRenderTurn(true));
    act(() => {
      a.unmount();
    });
    expect(b.result.current).toBe(true);
  });

  it('이중 rAF 를 기다리는 중 탭이 숨으면 기다림을 취소하고 바로 차례를 넘긴다', () => {
    const a = renderHook(() => useWidgetRenderTurn(true));
    const b = renderHook(() => useWidgetRenderTurn(true));
    a.unmount();
    frame();
    expect(b.result.current).toBe(false);
    setVisibility('hidden');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(b.result.current).toBe(true);
  });
});
