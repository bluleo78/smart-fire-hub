import { act, render, renderHook } from '@testing-library/react';
import { createElement, startTransition, Suspense } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  __widgetRenderQueueTestApi,
  RENDER_TURN_RELEASE_MS,
  RENDERING_RELEASE_CAP_MS,
  useWidgetRenderTurn,
} from './widget-render-queue';

// 시험 API 는 운영 빌드(PROD)가 아니면 존재한다 — vitest 를 다른 mode 로 돌려도 있다
const api = __widgetRenderQueueTestApi!;

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

/**
 * 렌더는 시작했지만 커밋하지 못하는 위젯을 흉내 낸다 — 차례(turn===true)를 받아 렌더하는 도중 영원히 서스펜드해
 * 전환이 커밋되지 않으므로 반납(effect cleanup)이 오지 않는다. 훅은 렌더 단계에서 markRendering 을 이미 불렀다.
 */
function mountStuckRenderer() {
  const never = new Promise<void>(() => {});
  const onRender = vi.fn();
  const onTurnRender = vi.fn();
  function Stuck({ n }: { n: number }) {
    const turn = useWidgetRenderTurn(true);
    onRender();
    if (turn) onTurnRender();
    if (turn) throw never;
    void n; // n 은 같은 차례를 다시 렌더시키기 위한 prop 일 뿐 쓰지 않는다
    return null;
  }
  const element = (n: number) => createElement(Suspense, { fallback: null }, createElement(Stuck, { n }));
  const view = render(element(0));
  return {
    view,
    element,
    renders: () => onRender.mock.calls.length,
    // turn===true 로 그려진 횟수 — markRendering 이 실제로 다시 불렸는지 확인하는 데 쓴다
    turnRenders: () => onTurnRender.mock.calls.length,
  };
}

/** document.visibilityState 를 스텁한다 */
function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
}

describe('useWidgetRenderTurn', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] });
    setVisibility('visible');
    api.reset();
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
    // WD-1146: 렌더를 시작한 차례는 500ms 에 강제 반납하지 않고 렌더 중 상한(8초)에 넘긴다
    it('렌더가 시작된 차례는 500ms 가 지나도 넘기지 않고 RENDERING_RELEASE_CAP_MS 에 넘긴다', () => {
      const a = mountStuckRenderer();
      const b = renderHook(() => useWidgetRenderTurn(true));
      expect(a.renders()).toBeGreaterThan(1);
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      expect(b.result.current).toBe(false);
      act(() => {
        vi.advanceTimersByTime(RENDERING_RELEASE_CAP_MS);
      });
      tick();
      expect(b.result.current).toBe(true);
    });

    it('같은 차례가 여러 번 렌더돼도 렌더 중 상한은 처음 렌더부터 잰다', () => {
      const a = mountStuckRenderer();
      const b = renderHook(() => useWidgetRenderTurn(true));
      expect(a.renders()).toBeGreaterThan(1);
      act(() => {
        vi.advanceTimersByTime(RENDERING_RELEASE_CAP_MS - 100);
      });
      // 같은 차례를 다시 렌더시켜도(markRendering 재호출) 상한이 새로 시작되면 안 된다
      // 동기 rerender 는 대기 중인 transition(setTurn(true)) 레인을 빼고 그려 turn===false 가 되므로, 같은 transition 레인으로 일으킨다
      const before = a.turnRenders();
      act(() => {
        startTransition(() => a.view.rerender(a.element(1)));
      });
      expect(a.turnRenders()).toBeGreaterThan(before);
      act(() => {
        vi.advanceTimersByTime(50);
      });
      tick();
      expect(b.result.current).toBe(false);
      act(() => {
        vi.advanceTimersByTime(18);
      });
      tick();
      expect(b.result.current).toBe(true);
    });

    it('차례를 받은 위젯이 커밋하지 못해도 RENDER_TURN_RELEASE_MS 가 지나면 다음 위젯이 차례를 받는다', () => {
      api.enqueueRawGrant(() => {});
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
      const leave1 = api.enqueueRawGrant(() => {});
      const g2 = vi.fn();
      api.enqueueRawGrant(g2);
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
      api.enqueueRawGrant(g2);
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
      api.enqueueRawGrant(() => {});
      const g2 = vi.fn();
      api.enqueueRawGrant(g2);
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
      const leave1 = api.enqueueRawGrant(() => {});
      const g2 = vi.fn();
      api.enqueueRawGrant(g2);
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

    // WD-1163: 렌더를 시작한 차례는 앞 강제 차례의 커밋으로 끊기지 않는다 (capOverdue 와 무관한 경로)
    it('렌더를 시작한 차례는 앞 강제 차례가 커밋돼도 끊지 않고 렌더 중 상한까지 기다린다 (WD-1163)', () => {
      const releaseA = api.enqueueRawGrant(() => {});
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      // A 가 강제 반납돼 B 가 차례를 받고 곧바로 렌더를 시작한다 — 타이머는 처음부터 렌더 중 상한(8초)이다
      mountStuckRenderer();
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      const c = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        releaseA();
      });
      tick();
      expect(c.result.current).toBe(false);
      act(() => {
        vi.advanceTimersByTime(RENDERING_RELEASE_CAP_MS);
      });
      tick();
      expect(c.result.current).toBe(true);
    });

    // WD-1163: capOverdue 해제 고정 — 렌더 시작 전에 상한이 지나 표시된 차례가 렌더를 시작하면 표시를 지워야 한다
    it('렌더를 시작하면 밀린 강제 반납 표시(capOverdue)를 지워 앞 강제 차례가 커밋돼도 렌더 중 상한까지 기다린다 (WD-1163)', () => {
      const releaseA = api.enqueueRawGrant(() => {});
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      // B 는 A 가 아직 커밋하지 않아 상한(500ms)이 지나도 넘기지 못한다 — capOverdue 가 선다
      const grantB = () => {};
      api.enqueueRawGrant(grantB);
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      // 그 뒤에야 B 가 렌더를 시작한다 — capOverdue 를 지워야 한다
      api.markRendering(grantB);
      const c = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        releaseA();
      });
      tick();
      expect(c.result.current).toBe(false);
      act(() => {
        vi.advanceTimersByTime(RENDERING_RELEASE_CAP_MS);
      });
      tick();
      expect(c.result.current).toBe(true);
    });

    it('렌더를 시작하지 못한 밀린 차례는 앞 강제 차례가 커밋되면 곧바로 넘긴다', () => {
      const releaseA = api.enqueueRawGrant(() => {});
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      // B 는 렌더를 시작하지 않는 원시 grant — 상한이 지났지만 forcedOutstanding 때문에 넘기지 못하고 밀려 있다
      api.enqueueRawGrant(() => {});
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      tick();
      const c = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        releaseA();
      });
      tick();
      expect(c.result.current).toBe(true);
    });

    it('숨은 탭에서는 상한 반납도 바로 다음 차례를 준다', () => {
      setVisibility('hidden');
      api.enqueueRawGrant(() => {});
      const b = renderHook(() => useWidgetRenderTurn(true));
      act(() => {
        vi.advanceTimersByTime(RENDER_TURN_RELEASE_MS);
      });
      expect(b.result.current).toBe(true);
    });
  });
});
