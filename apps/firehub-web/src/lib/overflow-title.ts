/**
 * 요소가 가로로 잘렸으면 title 에 자기 글자 전체를 달고, 아니면 뗀다 (WD-663, WD-669)
 * - 왜: 잘림을 늘 관찰(ResizeObserver 등)하는 대신 툴팁이 필요한 순간(pointerenter·focus·입력)에만 한 번 잰다.
 * - 1px 여유: scrollWidth·clientWidth 는 정수로 반올림되므로, 다 들어가는 글자도 반올림 차이로 1px 넘쳐 보일 수 있다.
 *   예전 useHorizontalOverflow 와 같이 1px 까지는 잘리지 않은 것으로 본다(WD-669) — 잘리지 않은 값에 툴팁이 붙지 않게.
 * - 글자는 화면에 그려진 값을 그대로 쓴다 — 입력칸은 value, 그 밖은 textContent. 호출부가 글자를 따로 넘기지 않는다.
 * @param el - 말줄임(truncate)되는 글자 요소 또는 입력칸
 */
export function syncOverflowTitle(el: Element | null) {
  if (!el) return;
  if (el.scrollWidth - 1 > el.clientWidth) {
    el.setAttribute('title', (el instanceof HTMLInputElement ? el.value : el.textContent) ?? '');
  } else {
    el.removeAttribute('title');
  }
}
