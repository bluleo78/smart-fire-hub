import type { ReactNode, RefObject } from 'react';
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

import { useAIChat } from '../../hooks/queries/useAIChat';
import { useCanvasState } from '../../hooks/useCanvasState';
import type { AIMessage, AIMode, CanvasLayout } from '../../types/ai';
import { getWidget } from './widgets/WidgetRegistry';

interface AIContextValue {
  isOpen: boolean;
  mode: AIMode;
  currentSessionId: string | null;
  messages: AIMessage[];
  isStreaming: boolean;
  isThinking: boolean;
  isUploading: boolean;
  isLoadingHistory: boolean;
  streamingMessage: Partial<AIMessage> | null;
  pendingUserMessage: string | null;
  contextTokens: number | null;
  isCompacting: boolean;
  compactionStartedAt: number | null;
  /**
   * 마지막으로 패널이 열린 순간의 document.activeElement(WD-579).
   * ChatInput 의 포커스 가드와 AISidePanel 의 닫힘 시 포커스 복귀(#333)가 함께 쓴다.
   */
  openFocusOriginRef: RefObject<Element | null>;
  /** 빠른 입력에서 보내지 못한 글(WD-1029) — 패널 입력창이 비어 있으면 채우고 비운다 */
  restoreDraft: string | null;
  setRestoreDraft: (draft: string | null) => void;
  openAI: () => void;
  closeAI: () => void;
  toggleAI: () => void;
  setMode: (mode: AIMode) => void;
  sendMessage: (content: string, files?: File[]) => Promise<boolean>;
  /** 이력 로딩 중이면 그 대화에 묶어 보관했다가 로딩이 끝나면(같은 대화일 때만) 보낸다 — 「AI 에게 묻기」용 (WD-1004) */
  sendAfterHistoryLoaded: (content: string) => void;
  stopStreaming: () => void;
  startNewSession: () => void;
  /** 대화 삭제 성공 뒤 호출 — 지운 것이 지금 열린 대화면(스트리밍이면 서버 취소 후) 캔버스까지 초기화하며 새 대화로 바꾼다 (WD-972) */
  onSessionDeleted: (sessionId: string) => void;
  loadSession: (sessionId: string) => void;
  // Canvas state (native mode) -- grouped to prevent AIContextValue bloat
  canvas: ReturnType<typeof useCanvasState>;
}

const AICtx = createContext<AIContextValue | null>(null);

// eslint-disable-next-line react-refresh/only-export-components
export function useAI() {
  const ctx = useContext(AICtx);
  if (!ctx) throw new Error('useAI must be used within AIProvider');
  return ctx;
}

function getStoredMode(): AIMode {
  try {
    const stored = localStorage.getItem('ai-mode');
    // floating 모드는 UI에서 일시 숨김 — 잔존 값은 side 로 강제 폴백
    if (stored === 'floating') return 'side';
    if (stored === 'side' || stored === 'fullscreen' || stored === 'native') {
      return stored;
    }
  } catch {
    // ignore localStorage errors
  }
  return 'side';
}

export function AIProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  // 열림 순간의 포커스 위치를 기억한다(WD-579). layout effect 라 입력창 포커스(ChatInput 의 passive effect)보다 먼저,
  // 또 lazy 패널의 Suspense 바깥이라 청크 도착 전 열림 커밋에서 바로 실행된다.
  const openFocusOriginRef = useRef<Element | null>(null);
  useLayoutEffect(() => {
    if (isOpen) openFocusOriginRef.current = document.activeElement;
  }, [isOpen]);
  // 빠른 입력은 보내는 순간 패널을 열고 언마운트돼 자기 상태로 글을 되돌릴 수 없어 제공자가 들고 있는다(WD-1029)
  // 초안은 넣은 순간의 대화 id 와 함께 보관한다 — 다른 대화로 새어 들어가지 않게(WD-1050)
  const [draft, setDraft] = useState<{ content: string; sessionId: string | null } | null>(null);
  const [mode, setModeState] = useState<AIMode>(getStoredMode);

  const canvas = useCanvasState();
  const modeRef = useRef<AIMode>(getStoredMode());

  // Keep modeRef in sync so handleCanvasWidget doesn't capture stale mode
  useEffect(() => {
    modeRef.current = mode;
  }, [mode]);

  const handleCanvasWidget = useCallback((widget: { id: string; toolName: string; input: Record<string, unknown>; canvas?: CanvasLayout }) => {
    if (modeRef.current !== 'native') return;
    // Only place widgets that have a registered widget component
    if (!getWidget(widget.toolName)) return;
    canvas.addWidget({
      id: widget.id,
      toolName: widget.toolName,
      input: widget.input,
      layout: widget.canvas || { width: 'full', height: 'full' },
      timestamp: new Date().toISOString(),
    });
  }, [canvas]);

  const {
    messages,
    isStreaming,
    isThinking,
    isUploading,
    isLoadingHistory,
    streamingMessage,
    pendingUserMessage,
    currentSessionId,
    currentSessionIdRef,
    sendMessage,
    sendAfterHistoryLoaded,
    stopStreaming,
    startNewSession: startNewSessionBase,
    loadSession: loadSessionBase,
    handleSessionDeleted: handleSessionDeletedBase,
    contextTokens,
    isCompacting,
    compactionStartedAt,
  } = useAIChat({ onCanvasWidget: handleCanvasWidget });

  // 훅이 setCurrentSessionId 안에서 동기로 바꾸는 ref 로 초안을 지금 대화에 묶는다(커밋을 기다리지 않음, WD-1050)
  const setRestoreDraft = useCallback((content: string | null) => {
    setDraft(content == null ? null : { content, sessionId: currentSessionIdRef.current });
  }, [currentSessionIdRef]);

  // 렌더 중 상태 조정(WD-1050): 열림→닫힘 또는 대화 변경이면 초안을 비운다.
  // 닫힘→열림은 비우지 않는다 — 빠른 입력은 같은 배치에서 초안을 넣고 패널을 연다.
  const [prevIsOpen, setPrevIsOpen] = useState(isOpen);
  const [prevSessionId, setPrevSessionId] = useState(currentSessionId);
  if (prevIsOpen !== isOpen || prevSessionId !== currentSessionId) {
    setPrevIsOpen(isOpen);
    setPrevSessionId(currentSessionId);
    // 대화가 바뀐 경우엔 초안의 sessionId 가 새 대화와 다를 때만 비운다 — 같은 배치에 들어온 새 대화용 초안은 남긴다(WD-1050)
    const sessionChanged = prevSessionId !== currentSessionId;
    if ((prevIsOpen && !isOpen) || (sessionChanged && draft && draft.sessionId !== currentSessionId)) setDraft(null);
  }
  // 초안은 「보내지 못한 그 순간의 현재 대화」에 묶인다. 사용자가 다른 대화를 연 뒤 실패하면
  // 그 대화 입력창에 나타난다(그 대화에서 다시 보내라는 뜻) — 현재 대화와 같을 때만 입력창에 넘긴다(WD-1050).
  const restoreDraft = draft && draft.sessionId === currentSessionId ? draft.content : null;

  const openAI = useCallback(() => setIsOpen(true), []);
  const closeAI = useCallback(() => setIsOpen(false), []);
  const toggleAI = useCallback(() => setIsOpen(prev => !prev), []);

  const setMode = useCallback((newMode: AIMode) => {
    setModeState(newMode);
    try {
      localStorage.setItem('ai-mode', newMode);
    } catch {
      // ignore localStorage errors
    }
  }, []);

  const startNewSession = useCallback(() => {
    canvas.resetCanvas();
    startNewSessionBase();
  }, [canvas, startNewSessionBase]);

  const onSessionDeleted = useCallback((sessionId: string) => {
    // 훅이 새 대화로 바꿨을 때만 캔버스를 비운다(startNewSession 래퍼와 같은 효과)
    if (handleSessionDeletedBase(sessionId)) canvas.resetCanvas();
  }, [canvas, handleSessionDeletedBase]);

  const loadSession = useCallback(async (sessionId: string) => {
    canvas.resetCanvas();
    await loadSessionBase(sessionId);
  }, [canvas, loadSessionBase]);

  // Keyboard shortcut: Cmd/Ctrl+K
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        // Native mode handles its own Cmd/Ctrl+K for chat overlay toggle
        if (modeRef.current === 'native') return;
        e.preventDefault();
        setIsOpen(prev => !prev);
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const ctxValue = useMemo(
    () => ({
      isOpen,
      mode,
      currentSessionId,
      messages,
      isStreaming,
      isThinking,
      isUploading,
      isLoadingHistory,
      streamingMessage,
      pendingUserMessage,
      contextTokens,
      isCompacting,
      compactionStartedAt,
      openFocusOriginRef,
      restoreDraft,
      setRestoreDraft,
      openAI,
      closeAI,
      toggleAI,
      setMode,
      sendMessage,
      sendAfterHistoryLoaded,
      stopStreaming,
      startNewSession,
      onSessionDeleted,
      loadSession,
      canvas,
    }),
    [
      isOpen,
      mode,
      currentSessionId,
      messages,
      isStreaming,
      isThinking,
      isUploading,
      isLoadingHistory,
      streamingMessage,
      pendingUserMessage,
      contextTokens,
      isCompacting,
      compactionStartedAt,
      restoreDraft,
      setRestoreDraft,
      openAI,
      closeAI,
      toggleAI,
      setMode,
      sendMessage,
      sendAfterHistoryLoaded,
      stopStreaming,
      startNewSession,
      onSessionDeleted,
      loadSession,
      canvas,
    ]
  );

  return (
    <AICtx.Provider value={ctxValue}>
      {children}
    </AICtx.Provider>
  );
}
