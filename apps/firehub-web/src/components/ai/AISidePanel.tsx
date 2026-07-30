import { useCallback, useEffect, useRef, useState } from 'react';

import { cn } from '../../lib/utils';
import { AIChatPanel } from './AIChatPanel';
import { useAI } from './AIProvider';

const MIN_WIDTH = 320;
const MAX_WIDTH = 600;
const DEFAULT_WIDTH = 380;
/** 키보드 화살표 1회당 폭 변화량(px) — #332 NodeDetailDrawer와 동일 단위 */
const KEY_STEP = 16;

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

  // 포커스 복귀용 refs (#333). 닫힌 패널을 inert로 만들면 내부에 있던 포커스가 <body>로 유실되므로
  // 열 때의 트리거를 기억해 두고 닫힐 때 되돌린다(#328과 같은 결함을 새로 만들지 않기 위함).
  const panelRef = useRef<HTMLDivElement | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  // 초기값을 false로 두는 이유: AISidePanel은 lazy 로드라 "이미 열린 상태"로 처음 마운트될 수 있다.
  // isOpen으로 초기화하면 그 첫 오픈을 전환으로 보지 못해 트리거를 기억하지 못한다.
  const prevOpenRef = useRef(false);

  useEffect(() => {
    const wasOpen = prevOpenRef.current;
    prevOpenRef.current = isOpen;

    if (isOpen && !wasOpen) {
      // 열림: 패널 밖에 있던 트리거(AI 상태 칩 등)를 복귀 지점으로 보관.
      const active = document.activeElement;
      openerRef.current =
        active instanceof HTMLElement && active !== document.body && !panelRef.current?.contains(active)
          ? active
          : null;
      return;
    }

    // 닫힘: 포커스가 갈 곳을 잃었을 때만(패널 내부에 남아 있거나 이미 <body>) 여는 트리거로 되돌린다.
    // 브라우저는 inert가 붙은 서브트리의 포커스를 나중에 정리해 <body>로 떨어뜨린다 — 이 이펙트
    // 시점에는 아직 정리 전이라 activeElement가 패널 내부로 잡히므로 "패널 내부인지"로 판정한다.
    // 패널 밖 컨트롤(AI 상태 칩 등)로 닫았으면 포커스가 그 컨트롤에 남아 있으니 건드리지 않는다.
    if (!isOpen && wasOpen) {
      const active = document.activeElement;
      const stranded = !active || active === document.body || !!panelRef.current?.contains(active);
      const opener = openerRef.current;
      // 트리거가 사라졌거나 애초에 없었으면(단축키로 열었고 포커스가 없던 경우) 그대로 둔다.
      if (stranded && opener?.isConnected) opener.focus();
    }
  }, [isOpen]);

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

  /**
   * 키보드 폭 조절(#339) — 마우스 전용이던 핸들에 window-splitter 조작을 부여한다.
   *
   * 방향 규약: 이 패널은 오른쪽 고정이고 드래그 계산이 `startWidth + (startX - clientX)`라
   * "왼쪽으로 끌면 넓어진다". 화살표도 이를 따라 ←가 폭 증가 / →가 폭 감소다(#332와 동일).
   * aria-valuenow(=폭)와 화살표 방향이 반대로 보이지만, 사용자가 보는 것은 "핸들이 움직이는
   * 방향"이므로 마우스와 일치시키는 편이 혼란이 적다.
   */
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    let next: number | null = null;
    if (e.key === 'ArrowLeft') next = width + KEY_STEP;
    else if (e.key === 'ArrowRight') next = width - KEY_STEP;
    // Home/End는 노출한 aria-valuemin/max에 맞춘다 — Home=최소 폭, End=최대 폭.
    else if (e.key === 'Home') next = MIN_WIDTH;
    else if (e.key === 'End') next = MAX_WIDTH;
    if (next === null) return;
    // 화살표/Home/End가 배경 스크롤로 새는 것을 막는다.
    e.preventDefault();
    setWidth(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, next)));
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
      ref={panelRef}
      className={cn(
        'relative z-10 border-l bg-background transition-[width] duration-200 ease-in-out shrink-0',
        isOpen ? 'w-auto' : 'w-0 overflow-hidden border-l-0'
      )}
      style={{ width: isOpen ? width : 0 }}
      data-testid="ai-side-panel"
      // inert: 접힌 패널의 내부 컨트롤 9개를 포커스 순서와 접근성 트리에서 동시에 제거한다(#333).
      // w-0(+overflow-hidden)은 display:none/visibility:hidden이 아니라 포커스를 막지 못해,
      // 전 페이지에서 보이지 않는 탭 스톱이 남아 있었다. aria-hidden은 붙이지 않는다 — inert가 함의한다.
      inert={!isOpen}
    >
      {/* 좌측 리사이즈 핸들 — 마우스 드래그 + 키보드(←/→/Home/End) 양쪽 지원(#339, #332와 동일 처방).
          폭 1px이라 기본 포커스 링이 보이지 않으므로 ring을 안쪽으로 명시한다.
          접힌 패널에서는 부모의 inert가 이 탭 스톱까지 함께 제거한다(#333 회귀 방지). */}
      <div
        className="absolute left-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/20 active:bg-primary/30 focus-visible:bg-primary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary z-10"
        onMouseDown={handleMouseDown}
        onKeyDown={handleKeyDown}
        tabIndex={0}
        role="separator"
        aria-orientation="vertical"
        aria-label="AI 패널 폭 조절"
        aria-valuenow={width}
        aria-valuemin={MIN_WIDTH}
        aria-valuemax={MAX_WIDTH}
        data-testid="ai-side-panel-resize-handle"
      />
      <div className="h-full overflow-hidden">
        <AIChatPanel />
      </div>
    </div>
  );
}
