import { useEffect } from 'react';

import { AIChatPanel } from './AIChatPanel';
import { useAI } from './AIProvider';
import { AISessionSidebar } from './AISessionSidebar';

export function AIFullScreen() {
  const { setMode } = useAI();

  /**
   * 전체 화면에서 Escape → 사이드 패널로 돌아간다 (WD-61).
   * AppLayout 이 열린 전체 화면 모드일 때만 이 컴포넌트를 마운트하므로 다른 모드에는 영향이 없다.
   * 전체 화면은 페이지를 덮으므로 포커스 위치와 무관하게 window 에서 받는다.
   * 스트리밍 중이어도 응답은 중단하지 않고 모드만 바꾼다.
   */
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      // 열린 다이얼로그·드롭다운·팝오버(Radix)는 document 캡처 단계에서 Escape 를 받아 레이어를 닫고
      // preventDefault 한다. 버블 단계의 window 리스너는 그 뒤에 실행되므로, 이미 소비된 Escape 는 무시한다.
      // 규칙: Radix 가 아닌 직접 만든 레이어(AINotificationPanel, AIStatusChip 드롭다운 등)도 Escape 로 닫을 때
      // 반드시 preventDefault 해야 레이어와 전체 화면이 함께 닫히지 않는다.
      if (e.defaultPrevented) return;
      // 한글 등 IME 조합 중 Escape 는 조합 취소용이다 (WD-54 와 같은 이유).
      if (e.isComposing || e.keyCode === 229) return;
      setMode('side');
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [setMode]);

  return (
    <div className="flex-1 flex h-full w-full">
      <AISessionSidebar />
      <div className="flex-1 h-full w-full">
        <AIChatPanel showSessionSwitcher={false} />
      </div>
    </div>
  );
}
