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
  openAI: () => void;
  closeAI: () => void;
  toggleAI: () => void;
  setMode: (mode: AIMode) => void;
  sendMessage: (content: string, files?: File[]) => void;
  stopStreaming: () => void;
  startNewSession: () => void;
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
    sendMessage,
    stopStreaming,
    startNewSession: startNewSessionBase,
    loadSession: loadSessionBase,
    contextTokens,
    isCompacting,
    compactionStartedAt,
  } = useAIChat({ onCanvasWidget: handleCanvasWidget });

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
      openAI,
      closeAI,
      toggleAI,
      setMode,
      sendMessage,
      stopStreaming,
      startNewSession,
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
      openAI,
      closeAI,
      toggleAI,
      setMode,
      sendMessage,
      stopStreaming,
      startNewSession,
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
