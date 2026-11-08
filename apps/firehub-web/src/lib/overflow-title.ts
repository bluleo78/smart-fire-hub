/**
 * 요소가 가로로 잘렸으면 title 에 자기 글자 전체를 달고, 아니면 뗀다 (WD-663)
 * - 왜: 잘림을 늘 관찰(ResizeObserver 등)하는 대신 툴팁이 필요한 순간(pointerenter·focus)에만 한 번 잰다.
 * - 화면에 그려진 글자(textContent)를 그대로 쓰므로 호출부가 글자를 따로 넘기지 않는다.
 * @param el - 말줄임(truncate)되는 글자 요소
 */
export function syncOverflowTitle(el: Element | null) {
  if (!el) return;
  if (el.scrollWidth > el.clientWidth) el.setAttribute('title', el.textContent ?? '');
  else el.removeAttribute('title');
}
