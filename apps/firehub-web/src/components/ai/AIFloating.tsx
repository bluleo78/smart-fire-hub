import { useCallback, useEffect,useRef, useState } from 'react';

import { AIChatPanel } from './AIChatPanel';
import { useAI } from './AIProvider';

const MIN_WIDTH = 300;
const MIN_HEIGHT = 400;
const DEFAULT_WIDTH = 380;
const DEFAULT_HEIGHT = 520;

interface FloatingPos {
  x: number;
  y: number;
  width: number;
  height: number;
}

function getStoredPosition() {
  try {
    const stored = localStorage.getItem('ai-floating-pos');
    if (stored) return JSON.parse(stored);
  } catch {
    // ignore
  }
  return null;
}

function savePosition(pos: { x: number; y: number; width: number; height: number }) {
  try {
    localStorage.setItem('ai-floating-pos', JSON.stringify(pos));
  } catch {
    // ignore
  }
}

export function AIFloating() {
  const { isOpen } = useAI();
  const containerRef = useRef<HTMLDivElement>(null);

  const [pos, setPos] = useState<FloatingPos>(() => {
    const stored = getStoredPosition();
    if (stored) return stored;
    return {
      x: window.innerWidth - DEFAULT_WIDTH - 24,
      y: window.innerHeight - DEFAULT_HEIGHT - 80,
      width: DEFAULT_WIDTH,
      height: DEFAULT_HEIGHT,
    };
  });

  const isDragging = useRef(false);
  const isResizing = useRef<string | null>(null);
  const dragStart = useRef({ x: 0, y: 0, posX: 0, posY: 0, width: 0, height: 0 });

  useEffect(() => {
    savePosition(pos);
  }, [pos]);

  const handleDragStart = useCallback((e: React.MouseEvent) => {
    // Only allow dragging from the header area (top 40px)
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect || e.clientY - rect.top > 40) return;
    if ((e.target as HTMLElement).closest('button, a, input, textarea, select')) return;
    isDragging.current = true;
    dragStart.current = { x: e.clientX, y: e.clientY, posX: pos.x, posY: pos.y, width: pos.width, height: pos.height };
    document.body.style.userSelect = 'none';

    const handleMove = (e: MouseEvent) => {
      if (!isDragging.current) return;
      const dx = e.clientX - dragStart.current.x;
      const dy = e.clientY - dragStart.current.y;
      setPos(prev => ({
        ...prev,
        x: Math.max(0, Math.min(window.innerWidth - prev.width, dragStart.current.posX + dx)),
        y: Math.max(0, Math.min(window.innerHeight - 40, dragStart.current.posY + dy)),
      }));
    };

    const handleUp = () => {
      isDragging.current = false;
      document.body.style.userSelect = '';
      document.removeEventListener('mousemove', handleMove);
      document.removeEventListener('mouseup', handleUp);
    };

    document.addEventListener('mousemove', handleMove);
    document.addEventListener('mouseup', handleUp);
  }, [pos]);

  const handleResizeStart = useCallback((e: React.MouseEvent, direction: string) => {
    e.stopPropagation();
    isResizing.current = direction;
    dragStart.current = { x: e.clientX, y: e.clientY, posX: pos.x, posY: pos.y, width: pos.width, height: pos.height };
    document.body.style.userSelect = 'none';

    const handleMove = (e: MouseEvent) => {
      if (!isResizing.current) return;
      const dx = e.clientX - dragStart.current.x;
      const dy = e.clientY - dragStart.current.y;
      const dir = isResizing.current;

      setPos(prev => {
        let { x, y, width, height } = { ...prev };
        if (dir.includes('e')) width = Math.max(MIN_WIDTH, dragStart.current.width + dx);
        if (dir.includes('w')) {
          width = Math.max(MIN_WIDTH, dragStart.current.width - dx);
          x = dragStart.current.posX + dragStart.current.width - width;
        }
        if (dir.includes('s')) height = Math.max(MIN_HEIGHT, dragStart.current.height + dy);
        if (dir.includes('n')) {
          height = Math.max(MIN_HEIGHT, dragStart.current.height - dy);
          y = dragStart.current.posY + dragStart.current.height - height;
        }
        return { x, y, width, height };
      });
    };

    const handleUp = () => {
      isResizing.current = null;
      document.body.style.userSelect = '';
      document.removeEventListener('mousemove', handleMove);
      document.removeEventListener('mouseup', handleUp);
    };

    document.addEventListener('mousemove', handleMove);
    document.addEventListener('mouseup', handleUp);
  }, [pos]);

  if (!isOpen) return null;

  return (
    <div
      ref={containerRef}
      className="fixed z-50 rounded-lg border shadow-2xl overflow-hidden"
      style={{
        left: pos.x,
        top: pos.y,
        width: pos.width,
        height: pos.height,
      }}
      onMouseDown={handleDragStart}
    >
      {/* Resize handles */}
      <div className="absolute top-0 left-0 w-2 h-full cursor-w-resize" onMouseDown={(e) => handleResizeStart(e, 'w')} />
      <div className="absolute top-0 right-0 w-2 h-full cursor-e-resize" onMouseDown={(e) => handleResizeStart(e, 'e')} />
      <div className="absolute top-0 left-0 w-full h-2 cursor-n-resize" onMouseDown={(e) => handleResizeStart(e, 'n')} />
      <div className="absolute bottom-0 left-0 w-full h-2 cursor-s-resize" onMouseDown={(e) => handleResizeStart(e, 's')} />
      <div className="absolute top-0 left-0 w-3 h-3 cursor-nw-resize" onMouseDown={(e) => handleResizeStart(e, 'nw')} />
      <div className="absolute top-0 right-0 w-3 h-3 cursor-ne-resize" onMouseDown={(e) => handleResizeStart(e, 'ne')} />
      <div className="absolute bottom-0 left-0 w-3 h-3 cursor-sw-resize" onMouseDown={(e) => handleResizeStart(e, 'sw')} />
      <div className="absolute bottom-0 right-0 w-3 h-3 cursor-se-resize" onMouseDown={(e) => handleResizeStart(e, 'se')} />

      <AIChatPanel />
    </div>
  );
}
