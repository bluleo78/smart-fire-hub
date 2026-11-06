import { useLayoutEffect, useRef } from 'react';

import { Skeleton } from './skeleton';
import { TableCell, TableRow } from './table';

interface TableSkeletonRowsProps {
  columns: number;
  rows?: number;
  widths?: string[];
}

/**
 * 목록 표가 불러오는 동안 tbody 에 그리는 스켈레톤 행.
 *
 * 접근성(WD-518): 그려져 있는 동안 자기가 속한 표에 aria-busy="true" 를 걸어, 보조기기가 표를 「불러오는 중」으로
 * 알고 내용이 채워질 때까지 기다리게 한다(내리면 지운다). 스켈레톤 셀은 내용 없는 장식이라 aria-hidden 으로 숨겨
 * 이름 없는 빈 셀이 읽히지 않게 한다. tbody 안의 행이라 표 요소에 속성을 줄 수 없어 DOM 에서 가까운 table 을 찾아
 * 건다 — 이 컴포넌트를 쓰는 목록 16곳이 호출부 수정 없이 함께 고쳐진다.
 * (가짜 「불러오는 중」 행을 두는 방식은 표 낭독에 데이터 행으로 섞이고, 이미 채워진 채 나타난 live region 은
 * 대부분의 스크린리더가 읽지 않아 쓰지 않았다.)
 * 전제: 표 하나에 이 컴포넌트는 하나만 그리고, 호출부가 그 표의 aria-busy 를 직접 다루지 않는다(내릴 때 속성을 지운다).
 */
export function TableSkeletonRows({ columns, rows = 5, widths }: TableSkeletonRowsProps) {
  const firstRowRef = useRef<HTMLTableRowElement>(null);

  // 그리는 동안만 가까운 표에 aria-busy — 첫 화면에 그려지기 전에 걸리도록 layout effect
  useLayoutEffect(() => {
    const table = firstRowRef.current?.closest('table');
    if (!table) return;
    table.setAttribute('aria-busy', 'true');
    return () => table.removeAttribute('aria-busy');
  }, []);

  return (
    <>
      {Array.from({ length: rows }).map((_, i) => (
        <TableRow key={i} ref={i === 0 ? firstRowRef : undefined} aria-hidden="true">
          {Array.from({ length: columns }).map((_, j) => (
            <TableCell key={j}>
              <Skeleton className={`h-4 ${widths?.[j] ?? 'w-full'}`} />
            </TableCell>
          ))}
        </TableRow>
      ))}
    </>
  );
}
