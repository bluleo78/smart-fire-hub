/**
 * aria-disabled 로 잠근 버튼의 사용 불가 표시용 클래스 — PendingButton 과 SimplePagination 방향 버튼이 함께 쓴다(WD-932).
 * 기본 Button 스타일은 `disabled:` 만 흐리게 하므로 같은 모양을 `aria-disabled:` 로 맞춘다(ui/button 은 생성 파일이라 직접 수정하지 않음).
 * pointer-events-none 으로 잠금 중 마우스 클릭·hover 도 먹지 않는다(disabled 가 아니라 포커스는 남는다).
 * 공용 모듈로 둔 이유: ui 부품이 components 의 PendingButton 을 거꾸로 import 하지 않게 하려는 것.
 */
export const ARIA_DISABLED_CLASS = 'aria-disabled:pointer-events-none aria-disabled:cursor-not-allowed aria-disabled:opacity-50';
