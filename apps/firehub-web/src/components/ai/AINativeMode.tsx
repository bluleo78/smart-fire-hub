import { X } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';

import { useAI } from './AIProvider';
import { CanvasArea } from './canvas/CanvasArea';
import { ChatInput } from './ChatInput';
import { MessageList } from './MessageList';

export function AINativeMode() {
  const {
    canvas,
    messages,
    pendingUserMessage,
    streamingMessage,
    isStreaming,
    isThinking,
    isUploading,
    sendMessage,
    stopStreaming,
  } = useAI();

  const [isExpanded, setIsExpanded] = useState(false);

  const handleExpand = useCallback(() => setIsExpanded(true), []);
  const handleCollapse = useCallback(() => setIsExpanded(false), []);

  // Cmd/Ctrl+K toggles chat overlay
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setIsExpanded((prev) => !prev);
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // 스트리밍이 끝나면 자동으로 축소 — 상태 전이 감지 패턴이므로 effect 안 setState가 필수적
  const prevStreamingRef = useRef(isStreaming);
  useEffect(() => {
    if (prevStreamingRef.current && !isStreaming && isExpanded) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      handleCollapse();
    }
    prevStreamingRef.current = isStreaming;
  }, [isStreaming, isExpanded, handleCollapse]);

  return (
    <div className="relative flex-1 overflow-hidden bg-background">
      {/* Layer 1: Canvas — fills entire area, independent */}
      <div className="absolute inset-0 flex flex-col">
        <CanvasArea
          pages={canvas.pages}
          activePageIndex={canvas.activePageIndex}
          onPageChange={canvas.goToPage}
          onRemoveWidget={canvas.removeWidget}
        />
      </div>

      {/* Layer 2: Floating chat — everything floats on top of canvas */}

      {/* Dim backdrop */}
      {isExpanded && (
        <div
          className="absolute inset-0 bg-black/30 z-10"
          style={{ animation: 'canvas-dim-in 200ms ease-out' }}
          onClick={handleCollapse}
          aria-hidden="true"
        />
      )}

      {/* Floating chat container — one unit: messages + input */}
      <div className="absolute bottom-3 left-4 right-4 z-20 flex flex-col gap-2">
        {/* Messages panel — slides up when expanded */}
        <div
          className="flex flex-col bg-background/95 backdrop-blur-md border border-border rounded-xl shadow-2xl overflow-hidden"
          style={{
            maxHeight: isExpanded ? '50vh' : '0px',
            opacity: isExpanded ? 1 : 0,
            transition: 'max-height 300ms cubic-bezier(0.32, 0.72, 0, 1), opacity 200ms ease',
            pointerEvents: isExpanded ? 'auto' : 'none',
          }}
        >
          {/* Header */}
          <div className="flex items-center justify-between px-3 py-2 border-b border-border shrink-0">
            <span className="text-sm font-medium text-muted-foreground">대화</span>
            <button
              type="button"
              onClick={handleCollapse}
              className="flex h-6 w-6 items-center justify-center rounded-md text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              aria-label="채팅 닫기"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          {/* Messages */}
          <div className="flex-1 min-h-0 overflow-hidden" style={{ height: isExpanded ? '45vh' : '0px' }}>
            <MessageList
              messages={messages}
              pendingUserMessage={pendingUserMessage}
              streamingMessage={streamingMessage}
              isStreaming={isStreaming}
              isThinking={isThinking}
            />
          </div>
        </div>

        {/* Chat input — always visible */}
        <div
          className="bg-background/95 backdrop-blur-md border border-border rounded-xl shadow-lg"
          onFocusCapture={handleExpand}
        >
          <ChatInput
            onSend={sendMessage}
            onStop={stopStreaming}
            isStreaming={isStreaming}
            isUploading={isUploading}
          />
        </div>
      </div>
    </div>
  );
}
