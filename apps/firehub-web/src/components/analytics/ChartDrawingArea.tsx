import { useContext, useLayoutEffect } from 'react';

import { cn } from '../../lib/utils';
import { ChartFigureContext } from './chart-figure-context';

/**
 * 차트의 「그림」 부분만 보조기술에서 숨기는 래퍼(WD-826).
 *
 * 차트 값은 ChartAccessibleFigure 의 sr-only 표로 전달하므로 SVG/canvas 그림은 aria-hidden 으로 숨긴다.
 * 숨기는 범위를 figure 전체가 아니라 그림 영역으로 좁힌 이유: 뷰가 그림 옆에 띄우는 안내문(파이 음수 제외 안내,
 * 숫자 아닌 값 안내 등)은 화면에 보이는 실제 정보라 스크린리더도 읽어야 한다 — 그런 안내는 이 래퍼 밖에 둔다.
 *
 * 마운트되면 figure 에 「그림이 있다」고 등록한다 — figure 는 그때만 차트 이름·값 표를 낸다(뷰가 그림 대신
 * 안내만 돌려주면 이 래퍼가 없으므로 차트 이름·표도 없다). 페인트 전에 반영되도록 layout effect 를 쓴다.
 *
 * 크기: 항상 부모를 채운다(h-full w-full). fillParent(높이 미지정)면 ResponsiveContainer 의 height="100%" 가
 * 이 div 를 기준으로 계산되므로 부모 높이를 그대로 넘겨야 하고, 고정 높이면 부모 높이가 auto 라 h-full 이 auto 로
 * 풀려 자식 높이를 따른다 — 예전 figure 안쪽 div(h-full w-full)와 같은 크기 규칙이라 화면 픽셀이 바뀌지 않는다.
 * 높이를 직접 정해야 하는 뷰(히트맵 등)는 style 로 넘긴다(인라인 높이가 h-full 보다 우선한다).
 */
export function ChartDrawingArea({
  style,
  children,
}: {
  style?: React.CSSProperties;
  children: React.ReactNode;
}) {
  const { registerDrawing, tableMode } = useContext(ChartFigureContext);
  // 등록 해제 함수를 그대로 cleanup 으로 돌려준다(언마운트·figure 교체 시 등록이 남지 않게)
  useLayoutEffect(() => registerDrawing(), [registerDrawing]);
  // 표 모드(WD-832): 그림만 display:none 으로 숨긴다 — 언마운트하지 않아 등록·상태가 유지되고, 뷰의 안내문은 그대로 보인다
  return (
    <div
      aria-hidden="true"
      data-chart-drawing=""
      className={cn('h-full w-full', tableMode && 'hidden')}
      style={style}
    >
      {children}
    </div>
  );
}
