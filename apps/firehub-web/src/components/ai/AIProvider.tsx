import { createContext, useContext, useState, useCallback, useEffect, useMemo } from 'react';
import type { ReactNode } from 'react';
import type { AIMode, AIMessage } from '../../types/ai';
import { useAIChat } from '../../hooks/queries/useAIChat';

interface AIContextValue {
  isOpen: boolean;
  mode: AIMode;
  currentSessionId: string | null;
  messages: AIMessage[];
  isStreaming: boolean;
  isLoadingHistory: boolean;
  streamingMessage: Partial<AIMessage> | null;
  pendingUserMessage: string | null;
  openAI: () => void;
  closeAI: () => void;
  toggleAI: () => void;
  setMode: (mode: AIMode) => void;
  sendMessage: (content: string) => void;
  stopStreaming: () => void;
  startNewSession: () => void;
  loadSession: (sessionId: string) => void;
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
    if (stored === 'side' || stored === 'floating' || stored === 'fullscreen') {
      return stored;
    }
  } catch {
    // ignore localStorage errors
  }
  return 'side';
}

export function AIProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  const [mode, setModeState] = useState<AIMode>(getStoredMode);

  const {
    messages,
    isStreaming,
    isLoadingHistory,
    streamingMessage,
    pendingUserMessage,
    currentSessionId,
    sendMessage,
    stopStreaming,
    startNewSession,
    loadSession,
  } = useAIChat();

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

  // Keyboard shortcut: Cmd/Ctrl+K
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
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
      isLoadingHistory,
      streamingMessage,
      pendingUserMessage,
      openAI,
      closeAI,
      toggleAI,
      setMode,
      sendMessage,
      stopStreaming,
      startNewSession,
      loadSession,
    }),
    [
      isOpen,
      mode,
      currentSessionId,
      messages,
      isStreaming,
      isLoadingHistory,
      streamingMessage,
      pendingUserMessage,
      openAI,
      closeAI,
      toggleAI,
      setMode,
      sendMessage,
      stopStreaming,
      startNewSession,
      loadSession,
    ]
  );

  return (
    <AICtx.Provider value={ctxValue}>
      {children}
    </AICtx.Provider>
  );
}
