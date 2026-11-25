/** 앱 셸 본문(main)의 id — 건너뛰기 링크 대상(AppLayout)과 포커스 잃음 판정이 같은 값을 쓴다 */
export const MAIN_CONTENT_ID = 'main-content';

/** 본문 main#main-content 자체인지 — isFocusLost 가 쓰는 것과 같은 판정(같은 id 라도 main 이 아니면 아니다) */
export function isMainContent(el: Element | null): boolean {
  return !!el && el.id === MAIN_CONTENT_ID && el.tagName === 'MAIN';
}

/**
 * 포커스를 잃었는지 판정한다(WD-968 리뷰 후속).
 *
 * 무엇: 버튼이 사라지거나 비활성화되는 등 포커스가 있던 요소가 없어졌을 때, 포커스가 「갈 곳 없이 떨어진」 상태인지 본다.
 * 왜: 예전에는 body 로 떨어질 때만 잃은 것으로 봤다. 그런데 「본문으로 건너뛰기」 뒤에는 본문(main#main-content)에
 *     임시 tabindex=-1 이 남아, 대화상자가 닫히며 포커스를 body 대신 main 으로 돌려준다(WD-968). main 자체는 조작할 수 없는
 *     자리라 body 와 같이 「잃은」 것으로 봐야 화면마다의 포커스 복구(편집 모드 전환·시트 선택·재시도 버튼)가 계속 동작한다.
 *
 * 건너뛰기 직후처럼 사용자가 일부러 main 에 둔 포커스도 이 판정은 「잃음」으로 본다(판정 자체는 그대로).
 * 다만 ChatInput 처럼 비동기로 「초점을 끌어오는」 곳은 main 을 잃음으로 보지 않는다 — 사용자가 일부러 main 에 둔 자리를
 * 빼앗지 않기 위해서다(WD-1039).
 *
 * main 은 잃음으로 보지만, 더 나은 대상이 없을 때는 마지막 복귀 지점으로 쓸 수 있다(WD-980).
 *
 * @param active 볼 요소(기본: 지금 document.activeElement)
 * @returns 포커스가 없거나 body·본문(main#main-content) 자체에 있으면 true
 */
export function isFocusLost(active: Element | null = document.activeElement): boolean {
  return isFocusDropped(active) || isMainContent(active);
}

/**
 * 포커스가 갈 곳 없이 body 로 떨어졌는지만 본다(본문 main 은 잃음으로 보지 않는다) — WD-1138.
 * 쓰는 곳: isFocusLost(위 판정의 body 쪽 절반)와 DatasetCategoryChips 의 초점 복구(WD-1138) — 초점을 「끌어오는」 쪽이라
 * 사용자가 건너뛰기 링크로 일부러 main 에 둔 자리를 빼앗지 않는다(WD-1039).
 */
export function isFocusDropped(active: Element | null = document.activeElement): boolean {
  return !active || active === document.body;
}

/**
 * 본문 main 에 초점을 준다(WD-1039) — tabindex 속성이 없을 때만 임시 tabindex=-1 을 붙이고 focus() 한다.
 * 왜: main 은 기본으로 초점을 받지 못해, 마우스 누름으로 tabindex 가 떨어진 뒤(AppLayout releaseMainFocusTarget)
 *     main 으로 복귀하려 해도 focus() 가 무시돼 body 로 떨어졌다. 이미 tabindex 가 있으면 값을 바꾸지 않는다.
 */
export function focusMainContent(main: HTMLElement): void {
  if (!main.hasAttribute('tabindex')) main.setAttribute('tabindex', '-1');
  main.focus();
}

/**
 * 포커스를 잃은 동안에만 selector 대상으로 포커스를 옮긴다 (WD-1044, WD-1051).
 *
 * 무엇: 다음 프레임부터 deadlineMs 까지 매 프레임 대상을 다시 찾아 focus() 하고, 붙으면 멈춘다.
 * 왜: 대상이 그 사이 다시 그려지거나(캔버스가 노드를 다시 재며 잠깐 숨김) 아직 만들어지지 않아
 *     한 번의 focus() 로는 놓칠 수 있다 — 대상이 없어도 기한까지 포기하지 않는다.
 * 잃음(isFocusLost)이 아니면 사용자가 이미 다른 곳으로 옮긴 것이라 빼앗지 않고 멈춘다.
 * tabIndex -1 요소(메뉴 항목 등)도 사용자가 연 자리일 수 있어 잃음으로 보지 않는다(WD-1051 코드 리뷰).
 * preventScroll 은 대상 쪽 자체 스크롤·이동(예: React Flow)과 겹치지 않게.
 *
 * @param selector 포커스를 줄 요소의 CSS 선택자
 * @param deadlineMs 다시 시도하는 기한(기본 500ms)
 */
export function focusWhenLost(selector: string, deadlineMs = 500): void {
  const deadline = performance.now() + deadlineMs;
  const attempt = () => {
    if (!isFocusLost()) return;
    const target = document.querySelector<HTMLElement>(selector);
    if (target) {
      target.focus({ preventScroll: true });
      if (document.activeElement === target) return;
    }
    if (performance.now() < deadline) requestAnimationFrame(attempt);
  };
  requestAnimationFrame(attempt);
}
