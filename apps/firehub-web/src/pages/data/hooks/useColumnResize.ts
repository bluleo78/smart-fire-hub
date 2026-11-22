import { useCallback, useEffect, useRef,useState } from 'react';

import type { DatasetColumnResponse } from '../../../types/dataset';

interface UseColumnResizeOptions {
  columns: DatasetColumnResponse[];
  containerRef: React.RefObject<HTMLElement | null>;
  /**
   * 데이터 열 밖에 고정 폭으로 놓이는 열(선택 열·작업 열 등)의 폭 합계(px). 기본 40(선택 열만).
   * 이 값을 컨테이너 폭에서 빼고 데이터 열에 나눠야 표가 가로로 넘치지 않는다(WD-977).
   */
  reservedWidth?: number;
}

interface UseColumnResizeReturn {
  columnWidths: Record<string, number>;
  setColumnWidths: React.Dispatch<React.SetStateAction<Record<string, number>>>;
  startResize: (colKey: string, e: React.MouseEvent) => void;
}

export function useColumnResize({ columns, containerRef, reservedWidth = 40 }: UseColumnResizeOptions): UseColumnResizeReturn {
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});
  const resizeStateRef = useRef<{ colKey: string; startX: number; startWidth: number } | null>(null);
  // 사용자가 직접 끌어 바꾼 열 — 권한 응답 등으로 reservedWidth 가 바뀌어 재계산할 때도 이 열 폭은 보존한다(WD-977)
  const userResizedRef = useRef<Set<string>>(new Set());

  // 열 구성이 같으면 재조회·다음 페이지로 columns 참조만 바뀌어도 다시 계산하지 않는다(WD-977)
  const columnKeys = columns.map((c) => c.columnName ?? String(c.id)).join('|');

  // 컨테이너의 스크롤바를 뺀 폭(clientWidth). 첫 렌더 직후엔 가상 스크롤 행이 아직 없어 세로 스크롤바가 없다가
  // 행이 그려진 뒤 생기므로, 한 번 읽고 끝내지 않고 ResizeObserver 로 폭 변화(스크롤바 등장·창 크기 변경)를 따라간다(WD-977)
  const [containerWidth, setContainerWidth] = useState<number | null>(null);
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const update = () => setContainerWidth(el.clientWidth);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, [containerRef, columnKeys]);

  useEffect(() => {
    if (columnKeys === '') return;
    const keys = columnKeys.split('|');
    setColumnWidths((prev) => {
      const next: Record<string, number> = {};
      const availableWidth = (containerWidth ?? 800) - reservedWidth;
      const perCol = Math.max(120, Math.floor(availableWidth / keys.length));
      for (const key of keys) {
        // 사용자가 리사이즈한 열만 이전 폭을 유지하고, 자동 계산된 열은 reservedWidth 변경 시 다시 계산한다.
        // (첫 계산이 쓰기 권한 응답 전에 일어나 40 으로 굳는 것을 막는다)
        next[key] = (userResizedRef.current.has(key) && prev[key]) || perCol;
      }
      return next;
    });
  }, [columnKeys, containerWidth, reservedWidth]);

  const startResize = useCallback(
    (colKey: string, e: React.MouseEvent) => {
      e.preventDefault();
      const startX = e.clientX;
      const startWidth = columnWidths[colKey] ?? 120;
      resizeStateRef.current = { colKey, startX, startWidth };

      const onMouseMove = (moveEvent: MouseEvent) => {
        if (!resizeStateRef.current) return;
        const delta = moveEvent.clientX - resizeStateRef.current.startX;
        const newWidth = Math.min(800, Math.max(80, resizeStateRef.current.startWidth + delta));
        // 첫 이동 때 한 번만 사용자 리사이즈로 표시(WD-977)
        userResizedRef.current.add(resizeStateRef.current.colKey);
        setColumnWidths((prev) => ({ ...prev, [resizeStateRef.current!.colKey]: newWidth }));
      };

      const onMouseUp = () => {
        resizeStateRef.current = null;
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', onMouseUp);
      };

      document.addEventListener('mousemove', onMouseMove);
      document.addEventListener('mouseup', onMouseUp);
    },
    [columnWidths]
  );

  return { columnWidths, setColumnWidths, startResize };
}
