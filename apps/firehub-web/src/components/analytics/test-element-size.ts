/**
 * 차트 단위 시험용 크기 대역 (WD-826·WD-846)
 *
 * jsdom 에는 ResizeObserver 가 없고 요소 크기도 0 이라, recharts·nivo(ResponsiveWrapper)가 아무것도 그리지 않는다.
 * 여러 시험 파일이 같은 대역을 따로 두면 서로 어긋나므로 여기 한 곳에 둔다.
 */
import { vi } from 'vitest';

/**
 * ResizeObserver 스텁을 만든다 — size 를 주면 관찰 즉시 그 크기를 알리고, 없으면 아무것도 알리지 않는다(빈 구현).
 * 마운트만 필요하면 빈 구현을, svg 까지 그려야 하면 크기를 준다.
 */
export function createResizeObserverStub(size?: { width: number; height: number }) {
  return class StubResizeObserver {
    private readonly callback: ResizeObserverCallback;
    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }
    observe(target: Element) {
      if (!size) return;
      const entry = { target, contentRect: { ...size, top: 0, left: 0 } } as unknown as ResizeObserverEntry;
      this.callback([entry], this as unknown as ResizeObserver);
    }
    unobserve() {}
    disconnect() {}
  };
}

/**
 * 모든 요소의 크기를 width×height 로 알린다 — getBoundingClientRect(auto-sizer 의 첫 측정)와 ResizeObserver(이후 측정) 둘 다.
 * 돌려주는 함수로 되돌린다. getBoundingClientRect 는 spyOn 으로 바꿔, 직접 대입·복원할 때처럼 상속값이 own property 로 남지 않는다.
 */
export function mockElementSize(width: number, height: number): () => void {
  const spy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    width, height, top: 0, left: 0, right: width, bottom: height, x: 0, y: 0, toJSON: () => ({}),
  } as DOMRect);
  const original = globalThis.ResizeObserver;
  Object.defineProperty(globalThis, 'ResizeObserver', {
    value: createResizeObserverStub({ width, height }),
    configurable: true,
  });
  return () => {
    spy.mockRestore();
    Object.defineProperty(globalThis, 'ResizeObserver', { value: original, configurable: true });
  };
}
