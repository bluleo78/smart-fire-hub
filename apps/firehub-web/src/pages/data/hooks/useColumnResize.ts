import { useState, useEffect, useCallback, useRef } from 'react';
import type { DatasetColumnResponse } from '../../../types/dataset';

interface UseColumnResizeOptions {
  columns: DatasetColumnResponse[];
  containerRef: React.RefObject<HTMLElement | null>;
}

interface UseColumnResizeReturn {
  columnWidths: Record<string, number>;
  setColumnWidths: React.Dispatch<React.SetStateAction<Record<string, number>>>;
  startResize: (colKey: string, e: React.MouseEvent) => void;
}

export function useColumnResize({ columns, containerRef }: UseColumnResizeOptions): UseColumnResizeReturn {
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});
  const resizeStateRef = useRef<{ colKey: string; startX: number; startWidth: number } | null>(null);

  useEffect(() => {
    if (columns.length === 0) return;
    setColumnWidths((prev) => {
      const next: Record<string, number> = {};
      const container = containerRef.current;
      const containerWidth = container ? container.clientWidth : 800;
      const availableWidth = containerWidth - 40;
      const perCol = Math.max(120, Math.floor(availableWidth / columns.length));
      for (const col of columns) {
        const key = col.columnName ?? String(col.id);
        next[key] = prev[key] ?? perCol;
      }
      return next;
    });
  }, [columns, containerRef]);

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
