import { createContext } from 'react';

/**
 * 차트 figure ↔ 그림 영역 연결(WD-826).
 *
 * ChartAccessibleFigure 는 뷰가 실제로 그림(ChartDrawingArea)을 그렸을 때만 차트 이름과 값 대체 표를 낸다.
 * 뷰가 그림 대신 안내(숫자 아닌 값·X 가 숫자 아님·통계 컬럼 없음 등)만 돌려주면 화면에는 차트가 없는데
 * 스크린리더만 「차트 이름 + 값 표」를 읽게 되어 시각 사용자와 정보가 어긋나기 때문이다.
 * ChartDrawingArea 가 마운트될 때 register 를 부르고, 돌려받은 함수로 언마운트 시 등록을 푼다.
 * figure 밖(단독 사용·테스트)에서는 아무것도 하지 않는 기본값을 쓴다.
 */
export const ChartFigureContext = createContext<{ registerDrawing: () => () => void }>({
  registerDrawing: () => () => {},
});

/**
 * 숨긴 그림 영역 안의 recharts 차트 루트에 펼쳐 넣는 공용 props(WD-826).
 *
 * 차트 값은 figure 의 sr-only 표가 전달하므로 그림(ChartDrawingArea, aria-hidden) 안에는 조작 요소가 없어야 한다.
 * recharts 3 의 accessibilityLayer(기본 true)는 svg 에 tabindex=0·role="application"·키보드 핸들러를 붙여,
 * 숨긴 영역 안에 Tab 정지점과 클릭 포커스가 생긴다(axe aria-hidden-focus). accessibilityLayer=false 면 recharts 가
 * svg 에 tabindex·role 을 아예 붙이지 않으므로(RootSurface) tabIndex 를 따로 넘기지 않는다 — tabIndex=-1 을 넘기면
 * Tab 정지점은 없어도 클릭으로 숨긴 svg 에 포커스가 들어간다. 마우스 툴팁은 accessibilityLayer 와 무관하게 동작한다.
 * Pie 조각 묶음 <g> 의 tabindex(rootTabIndex, 기본 0)는 이 설정과 무관하므로 Pie 에는 rootTabIndex={-1} 을 따로 준다.
 */
export const HIDDEN_CHART_ROOT_PROPS = { accessibilityLayer: false } as const;
