/**
 * 포커스를 잃었는지 판정한다(WD-968 리뷰 후속).
 *
 * 무엇: 버튼이 사라지거나 비활성화되는 등 포커스가 있던 요소가 없어졌을 때, 포커스가 「갈 곳 없이 떨어진」 상태인지 본다.
 * 왜: 예전에는 body 로 떨어질 때만 잃은 것으로 봤다. 그런데 「본문으로 건너뛰기」 뒤에는 본문(main#main-content)에
 *     임시 tabindex=-1 이 남아, 대화상자가 닫히며 포커스를 body 대신 main 으로 돌려준다(WD-968). main 자체는 조작할 수 없는
 *     자리라 body 와 같이 「잃은」 것으로 봐야 화면마다의 포커스 복구(편집 모드 전환·시트 선택·재시도 버튼)가 계속 동작한다.
 *
 * 건너뛰기 직후처럼 사용자가 일부러 main 에 둔 포커스도 「잃음」으로 본다 — 그 사이 비동기 작업이 끝나면 포커스가 옮겨질 수 있지만,
 * main 은 조작할 것이 없는 자리라 옮겨도 잃는 것이 없고 판정을 단순하게 두는 편이 낫다(리뷰 후속 판단).
 *
 * @param active 볼 요소(기본: 지금 document.activeElement)
 * @returns 포커스가 없거나 body·본문(main#main-content) 자체에 있으면 true
 */
/** 앱 셸 본문(main)의 id — 건너뛰기 링크 대상(AppLayout)과 포커스 잃음 판정이 같은 값을 쓴다 */
export const MAIN_CONTENT_ID = 'main-content';

export function isFocusLost(active: Element | null = document.activeElement): boolean {
  if (!active || active === document.body) return true;
  return active.id === MAIN_CONTENT_ID && active.tagName === 'MAIN';
}
