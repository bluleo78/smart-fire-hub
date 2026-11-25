import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  __enqueueRawGrantForTest,
  __resetWidgetRenderQueueForTest,
  RENDER_TURN_RELEASE_MS,
  useWidgetRenderTurn,
} from './widget-render-queue';

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

  // WD-1113: 차례를 받고도 반납하지 못하는 위젯(커밋이 밀림)이 줄 전체를 세우지 않게 하는 상한
  describe('반납 시간 상한', () => {
    it('차례를 받은 위젯이 커밋하지 못해도 RENDER_TURN_RELEASE_MS 가 지나면 다음 위젯이 차례를 받는다', () => {
      __enqueueRawGrantForTest(() => {});
      const b = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS - 1);
      });
      expect(b.result.current).toBe(false);
      act(() => {
        vi.advanceTimersByTime(1);
      });
      tick();
      expect(b.result.current).toBe(true);
    });

    it('상한으로 반납된 뒤 늦게 온 반납은 다음 차례를 두 번 주지 않는다', () => {
      const leave1 = __enqueueRawGrantForTest(() => {});
      const g2 = vi.fn();
      __enqueueRawGrantForTest(g2);
      const c = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      expect(g2).toHaveBeenCalledTimes(1);
      expect(c.result.current).toBe(false);
      // 이미 쫓겨난 g1 의 늦은 반납 — g2 가 쥔 차례를 건드리면 안 된다
      leave1();
      tick();
      expect(g2).toHaveBeenCalledTimes(1);
      expect(c.result.current).toBe(false);
    });

    it('정상 반납되면 이전 차례의 상한 타이머가 다음 차례를 일찍 쫓아내지 않는다', () => {
      // a 는 커밋 직후 반납한다
      renderHook(() => useWidgetRenderTurn(true));
      const g2 = vi.fn();
      __enqueueRawGrantForTest(g2);
      const c = renderHook(() => useWidgetRenderTurn(true));
      tick();
      expect(g2).toHaveBeenCalledTimes(1);
      // g2 가 차례를 받은 시각 기준 상한 직전까지는 c 가 받지 못한다
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS - 1);
      });
      expect(c.result.current).toBe(false);
    });

    it('cap 으로 넘긴 차례가 커밋되지 않은 동안에는 다음 차례를 또 강제로 넘기지 않는다', () => {
      __enqueueRawGrantForTest(() => {});
      const g2 = vi.fn();
      __enqueueRawGrantForTest(g2);
      const c = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      expect(g2).toHaveBeenCalledTimes(1);
      // g1 이 아직 커밋하지 않았으므로 g2 의 상한이 지나도 c 에게 강제로 넘기지 않는다(한꺼번에 그리기 방지)
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      expect(c.result.current).toBe(false);
      act(() => {
        vi.advanceTimersByTime(2000);
      });
      tick();
      expect(c.result.current).toBe(false);
    });

    it('cap 으로 넘긴 차례가 반납되면 다음 강제 반납이 다시 가능해진다', () => {
      const leave1 = __enqueueRawGrantForTest(() => {});
      const g2 = vi.fn();
      __enqueueRawGrantForTest(g2);
      const c = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      expect(g2).toHaveBeenCalledTimes(1);
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS * 2);
      });
      tick();
      expect(c.result.current).toBe(false);
      // g1 이 커밋(정상 반납)하면 막혀 있던 g2 의 강제 반납이 풀린다
      leave1();
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      expect(c.result.current).toBe(true);
    });

    it('숨은 탭에서는 상한 반납도 바로 다음 차례를 준다', () => {
      setVisibility('hidden');
      __enqueueRawGrantForTest(() => {});
      const b = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      expect(b.result.current).toBe(true);
    });
  });
});
