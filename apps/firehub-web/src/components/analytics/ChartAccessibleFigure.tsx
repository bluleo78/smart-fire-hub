import { useCallback, useMemo, useState } from 'react';

import { cn } from '../../lib/utils';
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
 *
 * 표 모드(WD-832, view="table"): 그림 영역(ChartDrawingArea)이 context 의 tableMode 를 보고 자기에 `hidden`(display:none)을 걸고 같은 표를 화면에 보인다(sr-only 표를 따로 두지 않는다).
 * 그림은 마운트된 채라 ChartDrawingArea 등록(hasDrawing)이 유지되고, 다시 그림 모드로 오면 그림이 되살아난다 — 상태를 잃지 않는다.
 * 그림이 없고 안내만 있을 때(hasDrawing=false)는 표 모드여도 안내를 그대로 보인다.
 */
export function ChartAccessibleFigure({
  name,
  table,
  fillParent,
  view = 'chart',
  children,
}: {
  name: string;
  table: ChartDataTable;
  fillParent?: boolean;
  /** 'table' 이면 그림 대신 값 표를 화면에 보인다(WD-832) — 기본 'chart' */
  view?: 'chart' | 'table';
  children: React.ReactNode;
}) {
  // 마운트된 그림 영역 수 — 한 뷰가 그림 영역을 여럿 둘 수도 있어 불리언 대신 센다
  const [drawingCount, setDrawingCount] = useState(0);
  const registerDrawing = useCallback(() => {
    setDrawingCount((n) => n + 1);
    return () => setDrawingCount((n) => n - 1);
  }, []);
  const hasDrawing = drawingCount > 0;
  // 표 모드는 그림이 있을 때만 — 안내만 그리는 뷰는 숨길 그림이 없으니 안내를 그대로 둔다
  const showTable = view === 'table' && hasDrawing;
  // 표 모드 여부를 그림 영역에 알려 그림만 숨기게 한다(WD-832) — 안내문까지 숨기지 않도록 래퍼에는 hidden 을 걸지 않는다
  const contextValue = useMemo(() => ({ registerDrawing, tableMode: showTable }), [registerDrawing, showTable]);
  // 값 표 행 — 그림 등록(drawingCount)으로 다시 렌더돼도 셀을 다시 만들지 않게 table 기준으로 메모한다
  const tableHead = useMemo(
    () => (
      <tr>
        {table.headers.map((h, i) => (
          <th key={i} scope="col" className="border px-2 py-1 text-left">
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
            <td key={ci} className="border px-2 py-1 text-left">
              {cell}
            </td>
          ))}
        </tr>
      )),
    [table.rows],
  );
  const fill = fillParent ? 'h-full w-full' : '';
  // 표 모드 + fillParent: 안내문 + 표가 카드 높이를 넘지 않게 figure 를 세로 flex 로 두고 표 영역이 남은 높이를 차지·스크롤한다
  const flexFill = fillParent && showTable;
  return (
    <figure
      aria-label={hasDrawing ? name : undefined}
      role={hasDrawing ? undefined : 'presentation'}
      data-testid="chart-figure"
      data-has-drawing={hasDrawing ? 'true' : 'false'}
      className={cn('m-0', fill, flexFill && 'flex flex-col')}
    >
      {/* 레이아웃용 래퍼(fillParent 높이 전달) — 숨김은 뷰 안 ChartDrawingArea 가 그림에만 건다(WD-832, 표 모드에서도 안내문은 보인다) */}
      {/* 표 모드에서는 그림이 빠져 래퍼는 안내문 높이만 차지하도록 뷰 루트의 높이(100%·고정값)를 풀고 줄어들지 않게 한다 */}
      <div className={cn(showTable ? 'shrink-0 [&>*]:!h-auto' : fill) || undefined}>
        <ChartFigureContext.Provider value={contextValue}>{children}</ChartFigureContext.Provider>
      </div>
      {/* 화면에는 보이지 않고 스크린리더만 읽는 값 대체 표 — 그림이 있을 때만(안내만 있으면 표도 없다) */}
      {hasDrawing && !showTable && (
        <table className="sr-only">
          <caption>{table.caption}</caption>
          <thead>{tableHead}</thead>
          <tbody>{tableBody}</tbody>
        </table>
      )}
      {/* 표 모드(WD-832): 같은 값을 화면에 보이는 표로 — 행이 많으면 카드 안에서 스크롤한다(카드 높이 유지) */}
      {showTable && (
        // 키보드 접근(WD-832): 스크롤 영역은 Safari 등에서 자동으로 초점을 받지 않아 tabIndex=0 으로 Tab 에 닿게 하고,
        // role="region"+aria-label 로 무엇인지 알리며 초점 링을 보인다(대시보드 오류 안내의 tabIndex=0 role="group" 관례)
        <div
          tabIndex={0}
          role="region"
          aria-label={table.caption}
          className={cn(
            'overflow-auto focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            fillParent && 'min-h-0 flex-1',
          )}
        >
          <table className="w-full border-collapse text-xs">
            <caption className="text-xs text-muted-foreground text-left">{table.caption}</caption>
            {/* 스크롤해도 머리 줄이 남도록 고정(WD-832) — 배경을 칠해 아래 행이 비치지 않게 한다 */}
            <thead className="sticky top-0 bg-background">{tableHead}</thead>
            <tbody>{tableBody}</tbody>
          </table>
        </div>
      )}
    </figure>
  );
}
