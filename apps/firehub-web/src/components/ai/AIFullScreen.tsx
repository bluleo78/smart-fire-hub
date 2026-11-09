import { useCallback } from 'react';

import { useOverlayEscape } from '../../hooks/useOverlayEscape';
import { AIChatPanel } from './AIChatPanel';
import { useAI } from './AIProvider';
import { AISessionSidebar } from './AISessionSidebar';

export function AIFullScreen() {
  const { setMode } = useAI();

  /**
   * 전체 화면에서 Escape → 사이드 패널로 돌아간다 (WD-61).
   * AppLayout 이 열린 전체 화면 모드일 때만 이 컴포넌트를 마운트하므로 다른 모드에는 영향이 없다.
   * 전체 화면은 페이지를 덮으므로 포커스 위치와 무관하게 받는다. 소비된 Escape·IME 조합 중 Escape 무시 규칙은
   * 모바일 AI 오버레이(WD-649)와 같아 공용 훅(useOverlayEscape)으로 묶었다.
   * 스트리밍 중이어도 응답은 중단하지 않고 모드만 바꾼다.
   */
  const backToSide = useCallback(() => setMode('side'), [setMode]);
  useOverlayEscape(true, backToSide);

  return (
    <div className="flex-1 flex h-full w-full">
      <AISessionSidebar />
      <div className="flex-1 h-full w-full">
        <AIChatPanel showSessionSwitcher={false} />
      </div>
    </div>
  );
}
