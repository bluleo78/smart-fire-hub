import { useState, useRef, useCallback } from 'react';
import type { AIMode } from '../../types/ai';
import { useAI } from './AIProvider';
import { AIStatusChipDropdown } from './AIStatusChipDropdown';

type ChipState = 'idle' | 'streaming' | 'thinking' | 'error' | 'side' | 'fullscreen';

function getChipState(ctx: {
  isStreaming: boolean;
  isThinking: boolean;
  isOpen: boolean;
  mode: AIMode;
}): ChipState {
  // error state reserved for future use
  if (ctx.isStreaming) return 'streaming';
  if (ctx.isThinking) return 'thinking';
  if (ctx.isOpen && ctx.mode === 'fullscreen') return 'fullscreen';
  if (ctx.isOpen && ctx.mode === 'side') return 'side';
  return 'idle';
}

const chipStyles: Record<ChipState, React.CSSProperties> = {
  idle: {
    background: 'rgba(129,140,248,0.15)',
    border: '1px solid rgba(129,140,248,0.3)',
    color: 'rgba(129,140,248,1)',
  },
  streaming: {
    background: 'rgba(129,140,248,0.25)',
    border: '1px solid rgba(129,140,248,0.5)',
    color: 'rgba(129,140,248,1)',
    boxShadow: '0 0 12px rgba(129,140,248,0.2)',
  },
  thinking: {
    background: 'rgba(251,191,36,0.15)',
    border: '1px solid rgba(251,191,36,0.3)',
    color: 'rgba(251,191,36,1)',
  },
  error: {
    background: 'rgba(239,68,68,0.2)',
    border: '1px solid rgba(239,68,68,0.4)',
    color: 'rgba(239,68,68,1)',
  },
  side: {
    background: 'rgba(129,140,248,0.3)',
    border: '1px solid #818cf8',
    color: '#818cf8',
  },
  fullscreen: {
    background: 'linear-gradient(135deg, #818cf8, #6366f1)',
    border: '1px solid transparent',
    color: '#ffffff',
    boxShadow: '0 2px 8px rgba(129,140,248,0.3)',
  },
};

function SideIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" className="shrink-0">
      <rect x="1" y="1" width="14" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" fill="none" />
      <line x1="10" y1="1" x2="10" y2="15" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

function FullscreenIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" className="shrink-0">
      <rect x="1" y="1" width="14" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.15" />
    </svg>
  );
}

function StatusDot() {
  return (
    <span
      className="shrink-0 rounded-full"
      style={{ width: 6, height: 6, backgroundColor: '#4ade80' }}
    />
  );
}

function PulseIcon() {
  return (
    <span
      className="shrink-0"
      style={{
        animation: 'ai-chip-pulse 1.5s ease-in-out infinite',
        fontSize: 12,
        lineHeight: 1,
      }}
    >
      ✦
    </span>
  );
}

function ThinkingIcon() {
  return (
    <span className="shrink-0" style={{ fontSize: 12, lineHeight: 1 }}>
      ⚡
    </span>
  );
}

function ErrorIcon() {
  return (
    <span className="shrink-0 flex items-center gap-0.5" style={{ fontSize: 12, lineHeight: 1 }}>
      <span>✦</span>
      <span style={{ fontWeight: 700, fontSize: 10 }}>!</span>
    </span>
  );
}

function ProgressBar() {
  return (
    <div
      className="overflow-hidden rounded-full"
      style={{ width: 20, height: 3, backgroundColor: 'currentColor', opacity: 0.2 }}
    >
      <div
        className="rounded-full"
        style={{
          width: '30%',
          height: '100%',
          backgroundColor: 'currentColor',
          opacity: 0.8,
          animation: 'ai-chip-slide 1s ease-in-out infinite',
        }}
      />
    </div>
  );
}

function ChipIcon({ state }: { state: ChipState }) {
  switch (state) {
    case 'idle':
      return <StatusDot />;
    case 'streaming':
      return <PulseIcon />;
    case 'thinking':
      return <ThinkingIcon />;
    case 'error':
      return <ErrorIcon />;
    case 'side':
      return <SideIcon />;
    case 'fullscreen':
      return <FullscreenIcon />;
  }
}

function getChipLabel(
  state: ChipState,
  currentSessionId: string | null,
  messageCount: number,
  currentToolName: string | null,
): string {
  if (state === 'streaming') return '응답 생성 중';
  if (state === 'thinking') return currentToolName || '처리 중';
  if (currentSessionId) {
    return `AI 대화 · ${messageCount}건`;
  }
  return 'AI 어시스턴트';
}

export function AIStatusChip() {
  const {
    isOpen,
    mode,
    isStreaming,
    isThinking,
    openAI,
    closeAI,
    setMode,
    currentSessionId,
    messages,
    streamingMessage,
    sendMessage,
    startNewSession,
    contextTokens,
  } = useAI();

  const [showDropdown, setShowDropdown] = useState(false);
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const closeTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const inputFocusedRef = useRef(false);

  const handleMouseEnter = useCallback(() => {
    clearTimeout(closeTimerRef.current);
    hoverTimerRef.current = setTimeout(() => setShowDropdown(true), 200);
  }, []);

  const handleMouseLeave = useCallback(() => {
    clearTimeout(hoverTimerRef.current);
    if (!inputFocusedRef.current) {
      closeTimerRef.current = setTimeout(() => setShowDropdown(false), 300);
    }
  }, []);

  const handleCloseDropdown = useCallback(() => {
    setShowDropdown(false);
  }, []);

  const state = getChipState({ isStreaming, isThinking, isOpen, mode });
  const showProgressBar = state === 'streaming' || state === 'thinking';

  // Extract current tool name from streaming message
  const currentToolName = streamingMessage?.toolCalls?.length
    ? streamingMessage.toolCalls[streamingMessage.toolCalls.length - 1].name
    : null;

  const label = getChipLabel(state, currentSessionId, messages.length, currentToolName);

  const handleClick = () => {
    if (!isOpen) {
      setMode('side');
      openAI();
    } else if (mode === 'side') {
      setMode('fullscreen');
    } else {
      // fullscreen -> close
      closeAI();
    }
  };

  const style: React.CSSProperties = {
    ...chipStyles[state],
    borderRadius: 20,
    padding: '6px 16px',
    fontSize: 12,
    transition: 'all 200ms ease',
    cursor: 'pointer',
    userSelect: 'none',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    fontWeight: 500,
    lineHeight: 1,
    whiteSpace: 'nowrap',
  };

  return (
    <div
      className="relative z-20"
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      <div
        role="button"
        tabIndex={0}
        onClick={handleClick}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            handleClick();
          }
          if (e.key === 'Escape' && showDropdown) {
            e.preventDefault();
            setShowDropdown(false);
          }
        }}
        style={style}
        aria-label={`AI 상태: ${label}`}
        aria-haspopup="true"
        aria-expanded={showDropdown}
      >
        <ChipIcon state={state} />
        <span>{label}</span>
        {showProgressBar && <ProgressBar />}
      </div>
      {showDropdown && (
        <AIStatusChipDropdown
          isAIOpen={isOpen}
          mode={mode}
          onModeChange={setMode}
          onOpen={openAI}
          onClose={closeAI}
          onNewSession={startNewSession}
          onSendMessage={sendMessage}
          messages={messages}
          isStreaming={isStreaming}
          isThinking={isThinking}
          contextTokens={contextTokens}
          currentSessionId={currentSessionId}
          inputFocusedRef={inputFocusedRef}
          onCloseDropdown={handleCloseDropdown}
        />
      )}
    </div>
  );
}
