import { Bell, Bot, CheckCheck, ChevronLeft, ExternalLink, MessageCircle, X } from 'lucide-react';
import { useEffect, useRef,useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import ReportModal from '@/components/ai/ReportModal';

import type { ProactiveMessage } from '../../api/proactive';
import {
  useMarkAllAsRead,
  useMarkAsRead,
  useProactiveMessages,
} from '../../hooks/queries/useProactiveMessages';
import { timeAgo } from '../../lib/formatters';
import { getSections } from '../../lib/proactive-utils';

// ── Helpers ───────────────────────────────────────────────────────────────────

function getRelativeTime(dateStr: string): string {
  const days = Math.floor((Date.now() - new Date(dateStr).getTime()) / 86_400_000);
  if (days < 7) return timeAgo(dateStr);
  return new Date(dateStr).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
}

// ── Sub-components ─────────────────────────────────────────────────────────────

const REMARK_PLUGINS = [remarkGfm];

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-10 px-4 text-center">
      <div
        className="flex items-center justify-center w-10 h-10 rounded-full mb-3"
        style={{ background: 'color-mix(in oklch, var(--primary) 10%, transparent)' }}
      >
        <Bell className="h-5 w-5" style={{ color: 'var(--primary)', opacity: 0.5 }} />
      </div>
      <p className="text-sm font-medium text-foreground mb-1">새 알림이 없습니다</p>
      <p className="text-xs text-muted-foreground">AI 스마트 작업이 완료되면 여기에 표시됩니다</p>
    </div>
  );
}

function NotificationItem({
  message,
  onSelect,
  onMarkRead,
}: {
  message: ProactiveMessage;
  onSelect: (msg: ProactiveMessage) => void;
  onMarkRead: (id: number) => void;
}) {
  const isUnread = !message.read;
  const sections = getSections(message.content);
  const preview = sections[0]?.content?.slice(0, 80) ?? '';
  const relTime = getRelativeTime(message.createdAt);

  const handleClick = () => {
    if (isUnread) onMarkRead(message.id);
    onSelect(message);
  };

  return (
    <button
      type="button"
      className="w-full text-left group transition-colors hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/50"
      style={{
        borderLeft: `3px solid ${isUnread ? 'var(--primary)' : 'transparent'}`,
        background: isUnread ? 'color-mix(in oklch, var(--primary) 4%, transparent)' : 'transparent',
      }}
      onClick={handleClick}
      aria-label={`AI 인사이트: ${message.title}${isUnread ? ' (안 읽음)' : ''}`}
    >
      <div className="flex items-start gap-2.5 px-3 py-2.5">
        {/* Icon */}
        <div
          className="shrink-0 mt-0.5 flex items-center justify-center w-6 h-6 rounded-full"
          style={{
            background: isUnread
              ? 'color-mix(in oklch, var(--primary) 15%, transparent)'
              : 'color-mix(in oklch, var(--muted-foreground) 10%, transparent)',
          }}
        >
          <Bot
            className="h-3 w-3"
            style={{ color: isUnread ? 'var(--primary)' : 'var(--muted-foreground)' }}
          />
        </div>

        <div className="flex-1 min-w-0">
          {/* Title row */}
          <div className="flex items-center justify-between gap-2 mb-0.5">
            <span
              className="text-xs font-semibold truncate"
              style={{ color: isUnread ? 'var(--foreground)' : 'var(--muted-foreground)' }}
            >
              {message.title}
            </span>
            <span className="text-[10px] text-muted-foreground shrink-0">{relTime}</span>
          </div>

          {/* Job name */}
          {message.jobName && (
            <span className="text-[10px] text-muted-foreground/70 block mb-0.5 truncate">
              {message.jobName}
            </span>
          )}

          {/* Preview */}
          {preview && (
            <p className="text-[11px] text-muted-foreground/80 leading-relaxed line-clamp-2">
              {preview}
            </p>
          )}
        </div>

        {/* Unread dot */}
        {isUnread && (
          <span
            className="shrink-0 mt-1.5 w-1.5 h-1.5 rounded-full"
            style={{ backgroundColor: 'var(--primary)' }}
          />
        )}
      </div>
    </button>
  );
}

function DetailView({
  message,
  onBack,
  onAskAI,
  onClose,
}: {
  message: ProactiveMessage;
  onBack: () => void;
  onAskAI: (msg: ProactiveMessage) => void;
  onClose: () => void;
}) {
  // 리포트 모달 열림 상태
  const [reportModalOpen, setReportModalOpen] = useState(false);
  const sections = getSections(message.content);
  const relTime = getRelativeTime(message.createdAt);
  // 리포트 보기 링크 생성용 메타데이터
  const jobId = message.content.jobId as string | undefined;
  const executionId = message.content.executionId as string | undefined;

  return (
    <div className="flex flex-col h-full">
      {/* Detail header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/40">
        <button
          type="button"
          onClick={onBack}
          className="flex items-center justify-center h-6 w-6 rounded hover:bg-muted/60 transition-colors text-muted-foreground"
          aria-label="목록으로 돌아가기"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </button>
        <span className="flex-1 text-xs font-semibold text-foreground truncate">{message.title}</span>
        <span className="text-[10px] text-muted-foreground shrink-0">{relTime}</span>
      </div>

      {/* Job name */}
      {message.jobName && (
        <div className="px-3 pt-2 pb-0">
          <span className="text-[10px] text-muted-foreground/70 uppercase tracking-wider">
            {message.jobName}
          </span>
        </div>
      )}

      {/* Sections */}
      <div className="flex-1 overflow-y-auto px-3 py-2 space-y-3">
        {sections.length === 0 ? (
          <p className="text-xs text-muted-foreground">내용이 없습니다.</p>
        ) : (
          sections.map((section) => (
            <div key={section.key}>
              {sections.length > 1 && (
                <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider mb-1">
                  {section.label}
                </p>
              )}
              <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-1.5 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 text-xs leading-relaxed">
                <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
                  {section.content}
                </ReactMarkdown>
              </div>
            </div>
          ))
        )}
      </div>

      {/* Footer — 리포트 보기 + AI에게 물어보기 */}
      <div className="px-3 py-2.5 border-t border-border/40 space-y-1.5">
        {jobId && executionId && (
          <>
            <button
              onClick={() => setReportModalOpen(true)}
              className="flex items-center justify-center gap-1.5 w-full rounded-lg py-2 text-xs font-medium transition-colors hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/50"
              style={{
                background: 'var(--primary)',
                color: 'var(--primary-foreground)',
              }}
            >
              <ExternalLink className="h-3.5 w-3.5" />
              리포트 보기
            </button>
            <ReportModal
              open={reportModalOpen}
              onClose={() => {
                setReportModalOpen(false);
                onClose();
              }}
              jobId={Number(jobId)}
              executionId={Number(executionId)}
            />
          </>
        )}
        <button
          type="button"
          onClick={() => onAskAI(message)}
          className="flex items-center justify-center gap-1.5 w-full rounded-lg py-2 text-xs font-medium transition-colors hover:bg-primary/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/50"
          style={{
            background: 'color-mix(in oklch, var(--primary) 10%, transparent)',
            color: 'var(--primary)',
            border: '1px solid color-mix(in oklch, var(--primary) 20%, transparent)',
          }}
        >
          <MessageCircle className="h-3.5 w-3.5" />
          AI에게 물어보기
        </button>
      </div>
    </div>
  );
}

// ── Main Component ─────────────────────────────────────────────────────────────

interface AINotificationPanelProps {
  onClose: () => void;
  onAskAI: (content: string) => void;
}

export function AINotificationPanel({ onClose, onAskAI }: AINotificationPanelProps) {
  const [visible, setVisible] = useState(false);
  const [selectedMessage, setSelectedMessage] = useState<ProactiveMessage | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const { data: messages = [], isLoading } = useProactiveMessages({ limit: 50 });
  const markAsRead = useMarkAsRead();
  const markAllAsRead = useMarkAllAsRead();

  const unreadCount = messages.filter((m) => !m.read).length;

  // Mount animation
  useEffect(() => {
    const raf = requestAnimationFrame(() => setVisible(true));
    return () => cancelAnimationFrame(raf);
  }, []);

  // Close on Escape
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        if (selectedMessage) {
          setSelectedMessage(null);
        } else {
          onClose();
        }
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose, selectedMessage]);

  // Close on outside click
  useEffect(() => {
    function handleMouseDown(e: MouseEvent) {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    }
    document.addEventListener('mousedown', handleMouseDown);
    return () => document.removeEventListener('mousedown', handleMouseDown);
  }, [onClose]);

  const handleAskAI = (message: ProactiveMessage) => {
    const sections = getSections(message.content);
    const contextText = sections.map((s) => s.content).join('\n\n');
    onAskAI(
      `"${message.title}" 분석 결과에 대해 자세히 설명해 주세요.\n\n${contextText.slice(0, 500)}`,
    );
    onClose();
  };

  return (
    <div
      ref={panelRef}
      role="dialog"
      aria-label="AI 인사이트 알림"
      aria-modal="false"
      className="absolute w-80 h-[480px] rounded-xl border border-primary/30 bg-popover shadow-2xl backdrop-blur-xl overflow-hidden transition-all duration-150 flex flex-col"
      style={{
        top: '100%',
        right: '50%',
        transform: `translateX(50%) translateY(${visible ? '0' : '4px'})`,
        marginTop: 8,
        opacity: visible ? 1 : 0,
      }}
    >
      {selectedMessage ? (
        // Detail view
        <DetailView
          message={selectedMessage}
          onBack={() => setSelectedMessage(null)}
          onAskAI={handleAskAI}
          onClose={onClose}
        />
      ) : (
        // List view
        <>
          {/* Panel header */}
          <div className="flex items-center justify-between px-3 py-2.5 border-b border-border/40 shrink-0">
            <div className="flex items-center gap-2">
              <Bell className="h-3.5 w-3.5" style={{ color: 'var(--primary)' }} />
              <span className="text-sm font-semibold text-foreground">AI 인사이트</span>
              {unreadCount > 0 && (
                <span
                  className="flex items-center justify-center h-4 min-w-4 px-1 rounded-full text-[9px] font-bold"
                  style={{
                    backgroundColor: 'var(--destructive)',
                    color: 'var(--destructive-foreground)',
                  }}
                >
                  {unreadCount > 99 ? '99+' : unreadCount}
                </span>
              )}
            </div>
            <div className="flex items-center gap-0.5">
              {unreadCount > 0 && (
                <button
                  type="button"
                  onClick={() => markAllAsRead.mutate()}
                  disabled={markAllAsRead.isPending}
                  className="flex items-center gap-1 px-2 py-1 rounded text-[10px] text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors disabled:opacity-50"
                  aria-label="전체 읽음 처리"
                  title="전체 읽음"
                >
                  <CheckCheck className="h-3 w-3" />
                  전체 읽음
                </button>
              )}
              <button
                type="button"
                onClick={onClose}
                className="flex items-center justify-center h-6 w-6 rounded hover:bg-muted/60 transition-colors text-muted-foreground"
                aria-label="알림 패널 닫기"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>

          {/* Notification list */}
          <div
            className="flex-1 overflow-y-auto"
            style={{ minHeight: 0 }}
            role="list"
            aria-label="AI 인사이트 알림 목록"
          >
            {isLoading ? (
              <div className="flex items-center justify-center py-10">
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              </div>
            ) : messages.length === 0 ? (
              <EmptyState />
            ) : (
              <div className="divide-y divide-border/30" role="list">
                {messages.map((msg) => (
                  <div key={msg.id} role="listitem">
                    <NotificationItem
                      message={msg}
                      onSelect={setSelectedMessage}
                      onMarkRead={(id) => markAsRead.mutate(id)}
                    />
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
