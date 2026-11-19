import { useCallback, useMemo, useState } from 'react';

import type { ChartDataTable } from './chart-a11y';
import { ChartFigureContext } from './chart-figure-context';

/**
 * 차트 시각화를 접근 가능한 figure 로 감싼다(WD-826).
 *
 * 스크린리더는 SVG/canvas 차트의 값을 읽지 못하므로 같은 값을 sr-only 표로 제공하고,
 * figure 는 aria-label 로 무엇을 보여 주는 차트인지 알린다.
 * 그림 자체를 숨기는 aria-hidden 은 여기서 붙이지 않고 각 뷰의 그림 영역(ChartDrawingArea)에만 붙인다 —
 * 뷰가 그림 옆에 띄우는 안내문(파이 음수 제외·숫자 아닌 값 등)은 화면에 보이는 정보라 스크린리더도 읽어야 하기 때문이다.
 *
 * 그림이 있을 때만 이름·표를 낸다: 뷰가 그림 대신 안내만 돌려주면(ChartDrawingArea 미마운트) 화면에는 차트가 없으므로
 * figure 를 role="presentation"(이름 없음)으로 두고 값 표도 내지 않는다 — 안내문만 그대로 읽힌다.
 * 상태가 바뀌어도 요소 종류(figure)와 자식 위치는 그대로라 뷰가 다시 마운트되지 않는다.
 * fillParent 일 때는 기존 래퍼(div.h-full.w-full)를 figure 와 안쪽 div 가 대신해 레이아웃(높이)이 바뀌지 않게 한다.
 */
export function ChartAccessibleFigure({
  name,
  table,
  fillParent,
  children,
}: {
  name: string;
  table: ChartDataTable;
  fillParent?: boolean;
  children: React.ReactNode;
}) {
  // 마운트된 그림 영역 수 — 한 뷰가 그림 영역을 여럿 둘 수도 있어 불리언 대신 센다
  const [drawingCount, setDrawingCount] = useState(0);
  const registerDrawing = useCallback(() => {
    setDrawingCount((n) => n + 1);
    return () => setDrawingCount((n) => n - 1);
  }, []);
  const contextValue = useMemo(() => ({ registerDrawing }), [registerDrawing]);
  const hasDrawing = drawingCount > 0;
  // 값 표 행 — 그림 등록(drawingCount)으로 다시 렌더돼도 셀을 다시 만들지 않게 table 기준으로 메모한다
  const tableHead = useMemo(
    () => (
      <tr>
        {table.headers.map((h, i) => (
          <th key={i} scope="col">
            {h}
          </th>
        ))}
      </tr>
    ),
    [table.headers],
  );
  const tableBody = useMemo(
    () =>
      table.rows.map((row, ri) => (
        <tr key={ri}>
          {row.map((cell, ci) => (
            <td key={ci}>{cell}</td>
          ))}
        </tr>
      )),
    [table.rows],
  );
  const fill = fillParent ? 'h-full w-full' : '';
  return (
    <figure
      aria-label={hasDrawing ? name : undefined}
      role={hasDrawing ? undefined : 'presentation'}
      data-testid="chart-figure"
      data-has-drawing={hasDrawing ? 'true' : 'false'}
      className={`m-0 ${fill}`.trim()}
    >
      {/* 레이아웃용 래퍼(fillParent 높이 전달) — 숨김은 뷰 안 ChartDrawingArea 가 그림에만 건다 */}
      <div className={fill || undefined}>
        <ChartFigureContext.Provider value={contextValue}>{children}</ChartFigureContext.Provider>
      </div>
      {/* 화면에는 보이지 않고 스크린리더만 읽는 값 대체 표 — 그림이 있을 때만(안내만 있으면 표도 없다) */}
      {hasDrawing && (
        <table className="sr-only">
          <caption>{table.caption}</caption>
          <thead>{tableHead}</thead>
          <tbody>{tableBody}</tbody>
        </table>
      )}
    </figure>
  );
}
