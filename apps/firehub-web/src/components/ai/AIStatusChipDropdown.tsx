import { useState, useRef, useEffect, type MutableRefObject } from 'react';
import type { AIMode, AIMessage } from '../../types/ai';

interface AIStatusChipDropdownProps {
  isAIOpen: boolean;
  mode: AIMode;
  onModeChange: (mode: AIMode) => void;
  onOpen: () => void;
  onClose: () => void;
  onNewSession: () => void;
  onSendMessage: (content: string) => void;
  messages: AIMessage[];
  isStreaming: boolean;
  isThinking: boolean;
  contextTokens: number | null;
  currentSessionId: string | null;
  inputFocusedRef: MutableRefObject<boolean>;
  onCloseDropdown: () => void;
}

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

function StatusIndicator({ isStreaming, isThinking }: { isStreaming: boolean; isThinking: boolean }) {
  if (isStreaming) {
    return (
      <span className="flex items-center gap-1.5 text-xs text-indigo-400">
        <span className="inline-block w-1.5 h-1.5 rounded-full bg-indigo-400" style={{ animation: 'ai-chip-pulse 1.5s ease-in-out infinite' }} />
        응답 중
      </span>
    );
  }
  if (isThinking) {
    return (
      <span className="flex items-center gap-1.5 text-xs text-amber-400">
        <span className="inline-block w-1.5 h-1.5 rounded-full bg-amber-400" style={{ animation: 'ai-chip-pulse 1.5s ease-in-out infinite' }} />
        도구 실행 중
      </span>
    );
  }
  return (
    <span className="flex items-center gap-1.5 text-xs text-emerald-400">
      <span className="inline-block w-1.5 h-1.5 rounded-full bg-emerald-400" />
      대기 중
    </span>
  );
}

function TokenProgressBar({ contextTokens }: { contextTokens: number | null }) {
  const maxTokens = 200000;
  const used = contextTokens ?? 0;
  const ratio = Math.min(used / maxTokens, 1);
  const percent = Math.round(ratio * 100);

  return (
    <div className="space-y-1">
      <div className="flex justify-between text-[10px] text-muted-foreground">
        <span>토큰 사용량</span>
        <span>{used > 0 ? `${(used / 1000).toFixed(1)}K / 200K` : '- / 200K'}</span>
      </div>
      <div className="h-1 w-full rounded-full bg-muted/50 overflow-hidden">
        <div
          className="h-full rounded-full transition-all duration-300"
          style={{
            width: `${percent}%`,
            backgroundColor: ratio > 0.8 ? '#ef4444' : ratio > 0.5 ? '#f59e0b' : '#818cf8',
          }}
        />
      </div>
    </div>
  );
}

function ActionButton({
  icon,
  label,
  onClick,
  highlighted = false,
}: {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
  highlighted?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex flex-col items-center justify-center gap-1.5 rounded-lg p-2.5 text-xs transition-colors hover:bg-muted/60 ${
        highlighted ? 'border border-primary bg-primary/10 text-primary' : 'text-muted-foreground hover:text-foreground'
      }`}
      role="menuitem"
    >
      {icon}
      <span className="text-[11px]">{label}</span>
    </button>
  );
}

function QuickInput({
  onSend,
  onOpen,
  inputFocusedRef,
  onCloseDropdown,
}: {
  onSend: (content: string) => void;
  onOpen: () => void;
  inputFocusedRef: MutableRefObject<boolean>;
  onCloseDropdown: () => void;
}) {
  const [value, setValue] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const handleSend = () => {
    const trimmed = value.trim();
    if (!trimmed) return;
    onSend(trimmed);
    onOpen();
    setValue('');
    onCloseDropdown();
  };

  return (
    <div className="flex items-center gap-2">
      <input
        ref={inputRef}
        type="text"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onFocus={() => { inputFocusedRef.current = true; }}
        onBlur={() => { inputFocusedRef.current = false; }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            handleSend();
          }
          if (e.key === 'Escape') {
            e.preventDefault();
            onCloseDropdown();
          }
        }}
        placeholder="AI에게 질문하기..."
        className="flex-1 rounded-lg border border-border/50 bg-muted/30 px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground/60 outline-none focus:border-primary/50 focus:ring-1 focus:ring-primary/20 transition-colors"
      />
      <button
        type="button"
        onClick={handleSend}
        disabled={!value.trim()}
        className="flex items-center justify-center rounded-lg bg-primary/20 p-1.5 text-primary transition-colors hover:bg-primary/30 disabled:opacity-40 disabled:cursor-not-allowed"
      >
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M14 2L7 9" />
          <path d="M14 2L9.5 14L7 9L2 6.5L14 2Z" />
        </svg>
      </button>
    </div>
  );
}

export function AIStatusChipDropdown({
  isAIOpen,
  mode,
  onModeChange,
  onOpen,
  onClose,
  onNewSession,
  onSendMessage,
  messages,
  isStreaming,
  isThinking,
  contextTokens,
  currentSessionId,
  inputFocusedRef,
  onCloseDropdown,
}: AIStatusChipDropdownProps) {
  const [visible, setVisible] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Animation: mount -> visible
  useEffect(() => {
    const raf = requestAnimationFrame(() => setVisible(true));
    return () => cancelAnimationFrame(raf);
  }, []);

  const lastAssistantMessage = [...messages].reverse().find((m) => m.role === 'assistant');
  const lastPreview = lastAssistantMessage
    ? lastAssistantMessage.content.slice(0, 50) + (lastAssistantMessage.content.length > 50 ? '...' : '')
    : null;

  return (
    <div
      ref={dropdownRef}
      role="menu"
      aria-label="AI 상태 및 제어"
      className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-80 rounded-xl border border-primary/30 bg-popover shadow-2xl backdrop-blur-xl transition-all duration-150"
      style={{
        opacity: visible ? 1 : 0,
        transform: `translateX(-50%) translateY(${visible ? '0' : '4px'})`,
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCloseDropdown();
        }
      }}
    >
      <div className="p-3 space-y-3">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-sm" style={{ color: '#818cf8' }}>✦</span>
            <span className="text-sm font-medium text-foreground">AI 어시스턴트</span>
          </div>
          <StatusIndicator isStreaming={isStreaming} isThinking={isThinking} />
        </div>

        {/* Session info */}
        <div className="rounded-lg bg-muted/30 p-2.5 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-[10px] text-muted-foreground uppercase tracking-wider">현재 세션</span>
            <span className="text-xs text-foreground">
              {currentSessionId ? 'AI 대화' : '-'}
            </span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-[10px] text-muted-foreground">대화 수</span>
            <span className="text-xs text-foreground">{messages.length}건</span>
          </div>
          {isAIOpen && lastPreview && (
            <div className="pt-1 border-t border-border/30">
              <span className="text-[10px] text-muted-foreground">마지막 응답</span>
              <p className="text-xs text-muted-foreground/80 mt-0.5 leading-relaxed truncate">{lastPreview}</p>
            </div>
          )}
          <TokenProgressBar contextTokens={contextTokens} />
        </div>

        {/* Quick input (only when AI is closed) */}
        {!isAIOpen && (
          <QuickInput
            onSend={onSendMessage}
            onOpen={onOpen}
            inputFocusedRef={inputFocusedRef}
            onCloseDropdown={onCloseDropdown}
          />
        )}

        {/* Action buttons */}
        {!isAIOpen ? (
          /* Closed: 2x2 grid */
          <div className="grid grid-cols-2 gap-1.5">
            <ActionButton
              icon={<SideIcon />}
              label="사이드 열기"
              onClick={() => { onModeChange('side'); onOpen(); }}
            />
            <ActionButton
              icon={<FullscreenIcon />}
              label="풀스크린"
              onClick={() => { onModeChange('fullscreen'); onOpen(); }}
            />
            <ActionButton
              icon={<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><line x1="8" y1="3" x2="8" y2="13" /><line x1="3" y1="8" x2="13" y2="8" /></svg>}
              label="새 세션"
              onClick={onNewSession}
            />
            <ActionButton
              icon={<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><circle cx="8" cy="8" r="6" /><polyline points="8 4 8 8 11 10" /></svg>}
              label="세션 목록"
              onClick={onOpen}
            />
          </div>
        ) : (
          /* Open: mode switch 3-col + 2-col */
          <div className="space-y-1.5">
            <div className="grid grid-cols-3 gap-1.5">
              <ActionButton
                icon={<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><line x1="4" y1="4" x2="12" y2="12" /><line x1="12" y1="4" x2="4" y2="12" /></svg>}
                label="닫기"
                onClick={onClose}
              />
              <ActionButton
                icon={<SideIcon />}
                label="사이드"
                onClick={() => onModeChange('side')}
                highlighted={mode === 'side'}
              />
              <ActionButton
                icon={<FullscreenIcon />}
                label="풀스크린"
                onClick={() => onModeChange('fullscreen')}
                highlighted={mode === 'fullscreen'}
              />
            </div>
            <div className="grid grid-cols-2 gap-1.5">
              <ActionButton
                icon={<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><line x1="8" y1="3" x2="8" y2="13" /><line x1="3" y1="8" x2="13" y2="8" /></svg>}
                label="새 세션"
                onClick={onNewSession}
              />
              <ActionButton
                icon={<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><circle cx="8" cy="8" r="6" /><polyline points="8 4 8 8 11 10" /></svg>}
                label="세션 목록"
                onClick={onOpen}
              />
            </div>
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-center gap-2 pt-1 border-t border-border/30">
          <span className="text-[10px] text-muted-foreground/60">
            {isAIOpen ? '⌘K 사이드 토글 | 클릭 모드 순환' : '⌘K 사이드 토글'}
          </span>
        </div>
      </div>
    </div>
  );
}
