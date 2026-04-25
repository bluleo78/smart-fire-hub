import { useCallback, useEffect, useRef, useState } from 'react';

import { cn } from '../../lib/utils';
import { AIChatPanel } from './AIChatPanel';
import { useAI } from './AIProvider';

const MIN_WIDTH = 320;
const MAX_WIDTH = 600;
const DEFAULT_WIDTH = 380;

/** Tailwind lg 브레이크포인트(1024px) 미만이면 모바일로 판정 */
const MOBILE_MQ = '(max-width: 1023px)';

/**
 * 현재 뷰포트가 모바일 너비인지 반응형으로 감지한다.
 * matchMedia 리스너로 리사이즈 시 자동 갱신.
 */
function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(MOBILE_MQ).matches
  );

  useEffect(() => {
    const mql = window.matchMedia(MOBILE_MQ);
    const handler = (e: MediaQueryListEvent) => setIsMobile(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return isMobile;
}

/**
 * AI 사이드 패널 컴포넌트.
 *
 * - 데스크탑(lg 이상): flex row 내 고정 너비 패널로 표시. 좌측 핸들로 너비 조절 가능.
 * - 모바일(lg 미만): `fixed inset-0` 오버레이로 전체 화면 표시.
 *   → flex row 레이아웃에서 벗어나 메인 콘텐츠 너비를 잠식하지 않는다.
 */
export function AISidePanel() {
  const { isOpen } = useAI();
  const isMobile = useIsMobile();
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  const isDragging = useRef(false);
  const startX = useRef(0);
  const startWidth = useRef(0);

  /** 데스크탑 전용: 좌측 드래그 핸들로 패널 너비 조절 */
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    isDragging.current = true;
    startX.current = e.clientX;
    startWidth.current = width;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';

    const handleMouseMove = (e: MouseEvent) => {
      if (!isDragging.current) return;
      const delta = startX.current - e.clientX;
      const newWidth = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startWidth.current + delta));
      setWidth(newWidth);
    };

    const handleMouseUp = () => {
      isDragging.current = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  }, [width]);

  if (isMobile) {
    // 모바일: fixed 오버레이로 렌더링 — flex row 레이아웃에서 완전히 분리
    if (!isOpen) return null;
    return (
      <div className="fixed inset-0 z-50 bg-background flex flex-col">
        {/* AIChatPanel 내부에서 closeAI context 함수로 닫을 수 있음 */}
        <div className="h-full overflow-hidden">
          <AIChatPanel />
        </div>
      </div>
    );
  }

  // 데스크탑: 기존 flex row 내 인라인 패널
  return (
    <div
      className={cn(
        'relative z-10 border-l bg-background transition-[width] duration-200 ease-in-out shrink-0',
        isOpen ? 'w-auto' : 'w-0 overflow-hidden border-l-0'
      )}
      style={{ width: isOpen ? width : 0 }}
    >
      {/* Resize handle */}
      <div
        className="absolute left-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/20 active:bg-primary/30 z-10"
        onMouseDown={handleMouseDown}
      />
      <div className="h-full overflow-hidden">
        <AIChatPanel />
      </div>
    </div>
  );
}
