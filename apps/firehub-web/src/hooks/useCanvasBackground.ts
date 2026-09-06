import { useSyncExternalStore } from 'react';

/**
 * `--background` CSS 변수의 실제 계산값을 읽어 캔버스(Cytoscape) 스타일시트에 주입하기 위한 훅 (#507).
 *
 * 무엇: `getComputedStyle(document.documentElement)`로 현재 테마의 `--background`
 *       (예: `oklch(0.13 0.015 280)`) 값을 읽어 온다.
 * 왜: Cytoscape는 캔버스(2D context)에 직접 그리므로 스타일시트가 CSS 변수(`var(--background)`)를
 *     읽지 못해 리터럴 색이 필요했다(#377). 하지만 리터럴을 고정 상수로 박아 두면 테마 컬러
 *     (indigo/ocean/sunset)마다 다른 실제 `--background` 값과 어긋나, 엣지 라벨 배경 박스가
 *     캔버스 배경과 다른 색의 사각형 얼룩으로 보인다(#507).
 * 어떻게 변경을 감지하는가: 다크/라이트 전환(next-themes)과 테마 컬러 전환(useThemeColor)은
 *   모두 `document.documentElement`의 class를 바꾸는 방식으로 구현돼 있다. 두 훅(각각 다른
 *   컨텍스트/모듈)에 개별적으로 의존해 재계산 타이밍을 맞추는 대신, `useSyncExternalStore` +
 *   `MutationObserver`로 `class` 속성 변화를 직접 구독한다 — DOM이라는 외부 소스를 구독하는
 *   React 표준 패턴이며, effect 안에서 setState를 직접 호출하지 않아도 되고(react-hooks
 *   set-state-in-effect 회피) React 컴포넌트 트리에서의 effect 실행 순서(부모의 class 갱신이
 *   자식의 읽기보다 먼저 일어난다는 보장)에도 기대지 않는다.
 */
export function useCanvasBackground(): string {
  return useSyncExternalStore(subscribe, readComputedBackground, () => '');
}

// documentElement의 class 속성 변화를 구독한다 — 콜백이 곧 "값이 바뀌었을 수 있다"는 신호다.
function subscribe(onStoreChange: () => void): () => void {
  const observer = new MutationObserver(onStoreChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  return () => observer.disconnect();
}

// SSR/테스트 환경(window 없음)에서는 빈 문자열을 반환해 호출부가 리터럴 폴백을 쓰도록 한다.
function readComputedBackground(): string {
  if (typeof window === 'undefined') return '';
  return getComputedStyle(document.documentElement).getPropertyValue('--background').trim();
}
