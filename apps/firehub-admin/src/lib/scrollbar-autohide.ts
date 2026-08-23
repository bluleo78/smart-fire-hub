/**
 * 스크롤 중인 컨테이너에 `data-scrolling="true"`를 붙이는 전역 리스너.
 *
 * 무엇: 앱 전체에 단 한 번 설치되어, 스크롤이 발생한 엘리먼트에 속성을 세팅하고
 *       유휴 800ms 뒤 제거한다. 실제 스크롤바 표시는 `index.css`의 `[data-scrolling]` 규칙이 맡는다.
 * 왜:   참고 구현(smart-dcim SD-326)은 스크롤 컨테이너마다 훅(ref)을 붙이는 방식인데,
 *       firehub에는 `overflow-auto` 컨테이너가 72곳이라 전부 개조하는 비용이 크고
 *       새로 추가되는 컨테이너를 빠뜨리기 쉽다. 여기서는 **캡처 단계 전역 리스너 하나**로
 *       바꿔 모든 컨테이너(그리고 body로 portal되는 Radix 오버레이)를 자동으로 커버한다.
 *
 * 핵심 전제: **scroll 이벤트는 버블링하지 않는다.** 반드시 `capture: true`로 들어야 한다.
 */

/** 스크롤이 멎었다고 판단하기까지의 유휴 시간. */
export const SCROLLBAR_IDLE_MS = 800;

const ATTR = 'data-scrolling';

export function installScrollbarAutoHide(): () => void {
  // 엘리먼트별 독립 타이머 — 중첩 스크롤러(main + 내부 테이블)가 서로의 타이머를 지우면
  // 한쪽이 스크롤 중인데 스크롤바가 꺼진다. WeakMap이라 DOM에서 떨어진 노드는 자동 회수된다.
  const timers = new WeakMap<Element, ReturnType<typeof setTimeout>>();
  // uninstall 시 잔여 속성을 지우기 위한 추적. 순회가 필요하므로 WeakSet이 아니라 Set이며,
  // 타이머 만료·uninstall 시점에 반드시 delete해 떨어져 나간 노드를 붙들지 않게 한다.
  const marked = new Set<Element>();

  const onScroll = (event: Event) => {
    const target = event.target;
    // 문서 스크롤은 target이 Document다 — 속성을 붙일 곳이 없으므로 documentElement로 대체한다.
    const el =
      target instanceof Element
        ? target
        : target instanceof Document
          ? target.documentElement
          : null;
    if (!el) return;

    el.setAttribute(ATTR, 'true');
    marked.add(el);

    const prev = timers.get(el);
    if (prev) clearTimeout(prev);
    timers.set(
      el,
      setTimeout(() => {
        el.removeAttribute(ATTR);
        timers.delete(el);
        marked.delete(el);
      }, SCROLLBAR_IDLE_MS)
    );
  };

  document.addEventListener('scroll', onScroll, { capture: true, passive: true });

  return () => {
    document.removeEventListener('scroll', onScroll, { capture: true });
    for (const el of marked) {
      const t = timers.get(el);
      if (t) clearTimeout(t);
      el.removeAttribute(ATTR);
    }
    marked.clear();
  };
}
