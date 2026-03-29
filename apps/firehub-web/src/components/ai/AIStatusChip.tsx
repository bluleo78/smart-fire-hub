import { useState, useRef, useCallback, useEffect } from 'react';
import type { AIMode } from '../../types/ai';
import { useAI } from './AIProvider';
import { AIStatusChipDropdown } from './AIStatusChipDropdown';
import { AINotificationPanel } from './AINotificationPanel';
import { SideIcon, FloatingIcon, FullscreenIcon, NativeIcon } from './AIChipIcons';
import { Bell } from 'lucide-react';
import { useUnreadCount } from '../../hooks/queries/useProactiveMessages';

type ChipState = 'idle' | 'streaming' | 'thinking' | 'error' | 'side' | 'floating' | 'fullscreen' | 'native';

function getChipState(ctx: {
  isStreaming: boolean;
  isThinking: boolean;
  isOpen: boolean;
  mode: AIMode;
}): ChipState {
  // error state reserved for future use
  if (ctx.isStreaming) return 'streaming';
  if (ctx.isThinking) return 'thinking';
  if (ctx.isOpen && ctx.mode === 'native') return 'native';
  if (ctx.isOpen && ctx.mode === 'fullscreen') return 'fullscreen';
  if (ctx.isOpen && ctx.mode === 'floating') return 'floating';
  if (ctx.isOpen && ctx.mode === 'side') return 'side';
  return 'idle';
}

const chipStyles: Record<ChipState, React.CSSProperties> = {
  idle: {
    background: 'color-mix(in oklch, var(--primary) 15%, transparent)',
    border: '1px solid color-mix(in oklch, var(--primary) 30%, transparent)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  streaming: {
    background: 'color-mix(in oklch, var(--primary) 25%, transparent)',
    border: '1px solid color-mix(in oklch, var(--primary) 50%, transparent)',
    color: 'var(--primary)',
    boxShadow: '0 0 12px color-mix(in oklch, var(--primary) 20%, transparent)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  thinking: {
    background: 'color-mix(in oklch, var(--warning) 15%, transparent)',
    border: '1px solid color-mix(in oklch, var(--warning) 30%, transparent)',
    color: 'var(--warning)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  error: {
    background: 'color-mix(in oklch, var(--destructive) 20%, transparent)',
    border: '1px solid color-mix(in oklch, var(--destructive) 40%, transparent)',
    color: 'var(--destructive)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  side: {
    background: 'color-mix(in oklch, var(--primary) 30%, transparent)',
    border: '1px solid var(--primary)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  floating: {
    background: 'color-mix(in oklch, var(--primary) 30%, transparent)',
    border: '1px solid var(--primary)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  fullscreen: {
    background: 'color-mix(in oklch, var(--primary) 30%, transparent)',
    border: '1px solid var(--primary)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
  native: {
    background: 'color-mix(in oklch, var(--primary) 20%, transparent)',
    border: '1px solid color-mix(in oklch, var(--primary) 70%, transparent)',
    color: 'var(--primary)',
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
  },
};

function StatusDot() {
  return (
    <span
      className="shrink-0 rounded-full"
      style={{ width: 6, height: 6, backgroundColor: 'oklch(0.7 0.2 149)' }}
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
    case 'floating':
      return <FloatingIcon />;
    case 'fullscreen':
      return <FullscreenIcon />;
    case 'native':
      return <NativeIcon />;
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

  const { data: unreadCount = 0 } = useUnreadCount();

  const [showDropdown, setShowDropdown] = useState(false);
  const [showNotificationPanel, setShowNotificationPanel] = useState(false);
  const [isHovering, setIsHovering] = useState(false); // hover wait animation
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const closeTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const inputFocusedRef = useRef(false);

  useEffect(() => {
    return () => {
      clearTimeout(hoverTimerRef.current);
      clearTimeout(closeTimerRef.current);
    };
  }, []);

  const handleMouseEnter = useCallback(() => {
    clearTimeout(closeTimerRef.current);
    if (!showDropdown) {
      setIsHovering(true); // start border animation
    }
    hoverTimerRef.current = setTimeout(() => {
      setIsHovering(false);
      setShowDropdown(true);
    }, 3000);
  }, [showDropdown]);

  const handleMouseLeave = useCallback(() => {
    clearTimeout(hoverTimerRef.current);
    setIsHovering(false); // stop border animation
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
    // When dropdown is visible, ignore chip click (use dropdown buttons instead)
    if (showDropdown) return;

    // Mode rotation: closed → side → floating → fullscreen → closed
    // (native mode is hidden — not yet stable for production)
    if (!isOpen) {
      setMode('side');
      openAI();
    } else if (mode === 'side') {
      setMode('floating');
    } else if (mode === 'floating') {
      setMode('fullscreen');
    } else {
      closeAI();
    }

    // Reset hover animation and restart 2s timer
    clearTimeout(hoverTimerRef.current);
    setIsHovering(false);
    // Brief delay to restart animation (allows CSS to reset)
    requestAnimationFrame(() => {
      setIsHovering(true);
      hoverTimerRef.current = setTimeout(() => {
        setIsHovering(false);
        setShowDropdown(true);
      }, 3000);
    });
  };

  const style: React.CSSProperties = {
    ...chipStyles[state],
    borderRadius: 20,
    padding: '6px 16px',
    fontSize: 12,
    minWidth: 140,
    justifyContent: 'center',
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
    <div className="z-20 inline-flex items-center gap-1.5">
      {/* AI Status Chip — hover/click/dropdown 전용 */}
      <div
        className="relative inline-flex flex-col items-center"
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        <div
          className="relative overflow-hidden"
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
          {isHovering && (
            <div
              className="absolute bottom-0 left-2 right-2 overflow-hidden rounded-full"
              style={{ height: 2 }}
            >
              <div
                className="h-full rounded-full"
                style={{
                  backgroundColor: 'var(--primary)',
                  animation: 'ai-chip-hover-progress 3s linear forwards',
                }}
              />
            </div>
          )}
        </div>
        <span className="sr-only" aria-live="polite">{label}</span>
        {showDropdown && (
          <AIStatusChipDropdown
            isAIOpen={isOpen}
            mode={mode}
            onModeChange={setMode}
            onOpen={openAI}
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

      {/* Notification Bell — 완전 독립 영역 */}
      <div className="relative">
        <button
          type="button"
          className="relative flex items-center justify-center h-7 w-7 rounded-full hover:bg-muted/80 transition-colors"
          aria-label={unreadCount > 0 ? `안 읽은 AI 인사이트 ${unreadCount}개` : 'AI 인사이트 알림'}
          aria-haspopup="dialog"
          aria-expanded={showNotificationPanel}
          onClick={() => {
            setShowDropdown(false);
            setShowNotificationPanel((prev) => !prev);
          }}
        >
          <Bell className="h-4 w-4 text-muted-foreground" />
          {unreadCount > 0 && (
            <span
              className="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[9px] font-bold text-destructive-foreground"
              aria-live="polite"
            >
              {unreadCount > 99 ? '99+' : unreadCount}
            </span>
          )}
        </button>
        {showNotificationPanel && (
          <AINotificationPanel
            onClose={() => setShowNotificationPanel(false)}
            onAskAI={(content) => {
              setShowNotificationPanel(false);
              if (!isOpen) {
                setMode('side');
                openAI();
              }
              sendMessage(content);
            }}
          />
        )}
      </div>
    </div>
  );
}
