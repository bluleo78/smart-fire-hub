import { useEffect } from 'react';

/**
 * 한글 등 IME 조합 중에 눌린 키인지 — 조합 중 Escape 는 조합 취소용이라 레이어를 닫는 데 쓰지 않는다(WD-54).
 * isComposing 이 없는 브라우저·조합 처리 단계는 keyCode 229 로 온다. 오버레이 Escape(아래 훅)와 AI 상태 칩 드롭다운이 같은 판정을 쓴다.
 */
export function isImeComposing(e: KeyboardEvent): boolean {
  return e.isComposing || e.keyCode === 229;
}

/**
 * 화면을 덮는 오버레이(AI 전체 화면 WD-61, 모바일 AI 오버레이 WD-649)를 Escape 로 닫는 공용 훅.
 *
 * - 포커스 위치와 무관하게(body 여도) 받도록 window 의 버블 단계에서 듣는다.
 * - 열린 다이얼로그·드롭다운·팝오버(Radix)는 document 캡처 단계에서 Escape 를 받아 자기 레이어만 닫고 preventDefault 한다.
 *   버블 단계의 window 리스너는 그 뒤에 실행되므로, 이미 소비된(defaultPrevented) Escape 는 무시한다.
 *   규칙: Radix 가 아닌 직접 만든 레이어(AINotificationPanel, AIStatusChip 드롭다운 등)도 Escape 로 닫을 때
 *   반드시 preventDefault 해야 레이어와 오버레이가 함께 닫히지 않는다.
 * - 한글 등 IME 조합 중 Escape(isComposing, 또는 조합 처리 중 keyCode 229)는 조합 취소용이라 무시한다(WD-54 와 같은 이유).
 *
 * @param enabled 듣기 여부 — false 면 리스너를 걸지 않는다(예: 데스크탑 사이드 패널·닫힌 패널).
 * @param onEscape 닫기 동작. 바뀔 때마다 리스너를 다시 걸므로 호출부에서 useCallback 등으로 안정적으로 넘긴다.
 */
export function useOverlayEscape(enabled: boolean, onEscape: () => void) {
  useEffect(() => {
    if (!enabled) return;
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      if (e.defaultPrevented) return;
      if (isImeComposing(e)) return;
      onEscape();
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [enabled, onEscape]);
}
