import { MessageCircle, Monitor, PanelRight, Sparkles,X } from 'lucide-react';

import type { AIMode } from '../../types/ai';
import { Button } from '../ui/button';
import { useAI } from './AIProvider';
import { ChatInput } from './ChatInput';
import { MessageList } from './MessageList';
import { SessionSwitcher } from './SessionSwitcher';

interface AIChatPanelProps {
  showModeSwitch?: boolean;
  showSessionSwitcher?: boolean;
  className?: string;
}

const modeIcons: Record<AIMode, React.ReactNode> = {
  side: <PanelRight className="h-3.5 w-3.5" />,
  floating: <MessageCircle className="h-3.5 w-3.5" />,
  fullscreen: <Monitor className="h-3.5 w-3.5" />,
};

const modeLabels: Record<AIMode, string> = {
  side: '사이드 패널',
  floating: '플로팅',
  fullscreen: '전체 화면',
};

export function AIChatPanel({ showModeSwitch = true, showSessionSwitcher = true, className }: AIChatPanelProps) {
  const {
    mode,
    setMode,
    closeAI,
    currentSessionId,
    messages,
    isStreaming,
    isLoadingHistory,
    streamingMessage,
    pendingUserMessage,
    sendMessage,
    stopStreaming,
    startNewSession,
    loadSession,
  } = useAI();

  const hasMessages = messages.length > 0 || pendingUserMessage || streamingMessage;

  return (
    <div className={`flex flex-col h-full bg-background ${className || ''}`}>
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b">
        <div className="flex items-center gap-2 min-w-0">
          <Sparkles className="h-4 w-4 text-primary shrink-0" />
          <span className="text-sm font-medium truncate">AI 어시스턴트</span>
        </div>
        <div className="flex items-center gap-0.5 shrink-0">
          {showModeSwitch && (
            <>
              {(Object.keys(modeIcons) as AIMode[]).map((m) => (
                <Button
                  key={m}
                  variant={mode === m ? 'secondary' : 'ghost'}
                  size="icon"
                  className="h-7 w-7"
                  onClick={() => setMode(m)}
                  title={modeLabels[m]}
                >
                  {modeIcons[m]}
                </Button>
              ))}
            </>
          )}
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={closeAI}>
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* Session Switcher */}
      {showSessionSwitcher && (
        <div className="px-2 py-1.5 border-b">
          <SessionSwitcher currentSessionId={currentSessionId} onNewSession={startNewSession} onSelectSession={(session) => loadSession(session.sessionId)} />
        </div>
      )}

      {/* Content area - constrained to remaining space */}
      <div className="flex-1 min-h-0">
        {/* Empty state */}
        {!hasMessages && !isLoadingHistory && (
          <div className="h-full flex flex-col items-center justify-center px-4 text-center">
            <Sparkles className="h-8 w-8 text-muted-foreground/50 mb-3" />
            <p className="text-sm text-muted-foreground mb-1">AI 어시스턴트에게 물어보세요</p>
            <p className="text-xs text-muted-foreground/70">무엇이든 질문해 보세요</p>
          </div>
        )}

        {/* Loading history */}
        {isLoadingHistory && (
          <div className="h-full flex items-center justify-center">
            <div className="flex flex-col items-center gap-2">
              <div className="h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              <p className="text-xs text-muted-foreground">대화 이력을 불러오는 중...</p>
            </div>
          </div>
        )}

        {/* Message List */}
        {hasMessages && !isLoadingHistory && (
          <MessageList
            messages={messages}
            pendingUserMessage={pendingUserMessage}
            streamingMessage={streamingMessage}
            isStreaming={isStreaming}
          />
        )}
      </div>

      {/* Input */}
      <div className="border-t p-3">
        <ChatInput
          onSend={sendMessage}
          onStop={stopStreaming}
          isStreaming={isStreaming}
        />
      </div>
    </div>
  );
}
