/**
 * 스크롤 컨테이너 안의 요소를 `scrollIntoView({ block/inline: 'nearest' })` 처럼 보이는 영역으로 끌어온다 — 단, 그 컨테이너만 스크롤한다.
 *
 * 왜 `scrollIntoView` 를 쓰지 않나(WD-530): Chromium 은 `scrollIntoView` 대상 요소로 「순차 포커스 탐색 시작점」을 옮긴다.
 * 화면 진입(마운트) 때 사이드바 활성 메뉴·활성 탭을 `scrollIntoView` 로 끌어오면, 사용자의 첫 Tab 이 앱 셸 맨 앞의
 * 「본문으로 건너뛰기」 링크가 아니라 그 요소 바로 다음으로 가 버린다(실측: /analytics/queries 첫 Tab → 사이드바 「차트」).
 * `scrollTop`/`scrollLeft` 직접 대입은 시작점을 건드리지 않는다. 또 바깥 조상(본문·문서)까지 스크롤하지 않는다는 이점도 있다.
 *
 * 계산 기준은 테두리 상자가 아니라 컨테이너의 스크롤포트(clientTop/Left + clientWidth/Height — 테두리·스크롤바 제외)이고,
 * 컨테이너의 scroll-padding(예: 탭 바의 scroll-px-10 — 페이드 폭만큼 여유)을 `scrollIntoView` 와 같이 반영한다.
 * 이동량은 올림해 소수점 픽셀만큼 잘린 채 남지 않게 한다. 이미 보이면 움직이지 않는다('nearest').
 *
 * @param container 스크롤할 컨테이너(overflow 가 auto/scroll 인 요소)
 * @param el        보이게 할 컨테이너 안의 요소
 * @param axis      'y'(세로) 또는 'x'(가로)
 */
export function scrollIntoViewNearest(container: HTMLElement, el: HTMLElement, axis: 'x' | 'y') {
  const c = container.getBoundingClientRect();
  const r = el.getBoundingClientRect();
  const style = getComputedStyle(container);
  // scroll-padding 은 auto 이면 0 으로 본다(parseFloat('auto') → NaN)
  const pad = (v: string) => parseFloat(v) || 0;

  // 축에 따라 시작·끝 변, 스크롤포트 시작(테두리 건너뜀)·크기, scroll-padding, 스크롤 속성을 고른다
  const y = axis === 'y';
  const viewStart = (y ? c.top + container.clientTop : c.left + container.clientLeft) + pad(y ? style.scrollPaddingTop : style.scrollPaddingLeft);
  const viewEnd =
    (y ? c.top + container.clientTop + container.clientHeight : c.left + container.clientLeft + container.clientWidth) -
    pad(y ? style.scrollPaddingBottom : style.scrollPaddingRight);
  const itemStart = y ? r.top : r.left;
  const itemEnd = y ? r.bottom : r.right;
  const prop = y ? 'scrollTop' : 'scrollLeft';

  if (itemStart < viewStart) {
    container[prop] -= Math.ceil(viewStart - itemStart);
  } else if (itemEnd > viewEnd) {
    container[prop] += Math.ceil(itemEnd - viewEnd);
  }
}
