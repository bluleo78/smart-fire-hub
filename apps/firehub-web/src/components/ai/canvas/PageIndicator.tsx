import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useState } from 'react';

import type { CanvasPage } from '../../../types/ai';

interface PageIndicatorProps {
  pages: CanvasPage[];
  activeIndex: number;
  onPageChange: (index: number) => void;
}

export function PageIndicator({ pages, activeIndex, onPageChange }: PageIndicatorProps) {
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);

  if (pages.length <= 1) return null;

  return (
    <div className="flex items-center justify-center gap-2 py-2">
      <button
        type="button"
        onClick={() => onPageChange(Math.max(0, activeIndex - 1))}
        disabled={activeIndex === 0}
        className="flex h-6 w-6 items-center justify-center rounded-full text-muted-foreground hover:text-foreground hover:bg-muted transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
        aria-label="이전 페이지"
      >
        <ChevronLeft className="h-4 w-4" />
      </button>

      <div className="flex items-center gap-1.5">
        {pages.map((page, idx) => (
          <div key={page.id} className="relative">
            <button
              type="button"
              onClick={() => onPageChange(idx)}
              onMouseEnter={() => setHoveredIndex(idx)}
              onMouseLeave={() => setHoveredIndex(null)}
              className="flex items-center justify-center transition-all duration-200"
              aria-label={`페이지 ${idx + 1}: ${page.label}`}
              aria-current={idx === activeIndex ? 'true' : undefined}
            >
              <span
                className="rounded-full transition-all duration-200"
                style={{
                  width: idx === activeIndex ? 8 : 6,
                  height: idx === activeIndex ? 8 : 6,
                  backgroundColor: idx === activeIndex
                    ? 'var(--primary)'
                    : 'color-mix(in oklch, var(--primary) 30%, transparent)',
                }}
              />
            </button>
            {hoveredIndex === idx && (
              <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 whitespace-nowrap rounded-md bg-popover border border-border px-2 py-1 text-xs text-foreground shadow-md z-10">
                {page.label}
              </div>
            )}
          </div>
        ))}
      </div>

      <span className="text-xs text-muted-foreground min-w-[2.5rem] text-center">
        {activeIndex + 1}/{pages.length}
      </span>

      <button
        type="button"
        onClick={() => onPageChange(Math.min(pages.length - 1, activeIndex + 1))}
        disabled={activeIndex === pages.length - 1}
        className="flex h-6 w-6 items-center justify-center rounded-full text-muted-foreground hover:text-foreground hover:bg-muted transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
        aria-label="다음 페이지"
      >
        <ChevronRight className="h-4 w-4" />
      </button>
    </div>
  );
}
