import { Sparkles, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import type { CanvasPage as CanvasPageType } from '../../../types/ai';
import { Button } from '../../ui/button';
import { useAI } from '../AIProvider';
import { SessionSwitcher } from '../SessionSwitcher';
import { TokenUsageChip } from '../TokenUsageChip';
import { CanvasPage } from './CanvasPage';
import { PageIndicator } from './PageIndicator';

interface CanvasAreaProps {
  pages: CanvasPageType[];
  activePageIndex: number;
  onPageChange: (index: number) => void;
  onRemoveWidget: (pageId: string, widgetId: string) => void;
}

type SlideDirection = 'left' | 'right' | 'none';

export function CanvasArea({ pages, activePageIndex, onPageChange, onRemoveWidget }: CanvasAreaProps) {
  const {
    closeAI,
    currentSessionId,
    startNewSession,
    loadSession,
    contextTokens,
    isCompacting,
  } = useAI();

  const prevIndexRef = useRef(activePageIndex);
  const [direction, setDirection] = useState<SlideDirection>('none');
  const [displayIndex, setDisplayIndex] = useState(activePageIndex);

  // 페이지 인덱스 변화 감지 시 슬라이드 방향 전이 — 상태 전이 감지 패턴
  useEffect(() => {
    if (activePageIndex !== prevIndexRef.current) {
      const dir = activePageIndex > prevIndexRef.current ? 'left' : 'right';
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setDirection(dir);
       
      setDisplayIndex(activePageIndex);
      prevIndexRef.current = activePageIndex;
       
      const timer = setTimeout(() => setDirection('none'), 350);
      return () => clearTimeout(timer);
    }
  }, [activePageIndex]);

  const activePage = pages.length > 0 ? (pages[displayIndex] ?? pages[0]) : null;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Header — fullscreen style with session switcher */}
      <div className="flex items-center justify-between px-3 py-2 border-b gap-2 shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          <Sparkles className="h-4 w-4 text-primary shrink-0" />
          <span className="text-sm font-medium truncate">AI 어시스턴트</span>
        </div>

        <div className="flex items-center gap-1 shrink-0">
          <SessionSwitcher
            currentSessionId={currentSessionId}
            onNewSession={startNewSession}
            onSelectSession={(session) => loadSession(session.sessionId)}
          />
          <TokenUsageChip tokens={contextTokens} isCompacting={isCompacting} />
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={closeAI}>
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* Empty state */}
      {pages.length === 0 && (
        <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-2xl border-2 border-dashed border-border text-3xl opacity-40">
            ⬜
          </div>
          <div className="space-y-1">
            <p className="text-sm font-medium text-foreground">캔버스가 비어 있습니다</p>
            <p className="text-xs text-muted-foreground">
              AI에게 요청하면 결과가 캔버스에 배치됩니다
            </p>
          </div>
        </div>
      )}

      {/* Page label */}
      {activePage && pages.length > 0 && (
        <div className="flex items-center justify-between px-4 py-1.5 border-b border-border/50 shrink-0">
          <span className="text-xs font-medium text-muted-foreground">
            {activePage.label}
          </span>
          <span className="text-xs text-muted-foreground/60">
            {activePage.widgets.length}개 위젯
          </span>
        </div>
      )}

      {/* Active page */}
      {activePage && (
        <CanvasPage
          key={activePage.id}
          page={activePage}
          onRemoveWidget={onRemoveWidget}
          direction={direction}
        />
      )}

      {/* Page indicator */}
      {pages.length > 1 && (
        <div className="shrink-0">
          <PageIndicator
            pages={pages}
            activeIndex={activePageIndex}
            onPageChange={onPageChange}
          />
        </div>
      )}
    </div>
  );
}
