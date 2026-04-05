import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Bot, ExternalLink } from 'lucide-react';

import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import ReportModal from '@/components/ai/ReportModal';
import type { ProactiveMessage as ProactiveMessageType } from '../../api/proactive';
import { getSections } from '../../lib/proactive-utils';

interface ProactiveMessageProps {
  message: ProactiveMessageType;
  onMarkRead: (id: number) => void;
  onFollowUp: (message: ProactiveMessageType) => void;
}

const REMARK_PLUGINS = [remarkGfm];

export function ProactiveMessage({ message, onMarkRead, onFollowUp }: ProactiveMessageProps) {
  // 리포트 모달 열림 상태
  const [reportModalOpen, setReportModalOpen] = useState(false);
  const isUnread = !message.read;
  const sections = getSections(message.content);
  // ChatDeliveryChannel이 저장한 jobId/executionId — 리포트 보기 링크 생성에 사용
  const jobId = message.content.jobId as string | undefined;
  const executionId = message.content.executionId as string | undefined;
  const time = new Date(message.createdAt).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
  });

  const handleClick = () => {
    if (isUnread) {
      onMarkRead(message.id);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleClick();
    }
  };

  return (
    <div
      className="rounded-lg border bg-card text-card-foreground"
      style={{
        borderLeftWidth: 4,
        borderLeftColor: isUnread ? 'var(--primary)' : 'var(--border)',
      }}
      role="button"
      tabIndex={0}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
      aria-label={`AI 인사이트: ${message.title}${isUnread ? ' (안 읽음)' : ''}`}
    >
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2">
        <Bot className="h-4 w-4 text-primary shrink-0" />
        <span className="flex-1 text-sm font-medium truncate">{message.title}</span>
        <span className="text-xs text-muted-foreground shrink-0">{time}</span>
        {isUnread && (
          <Badge variant="secondary" className="shrink-0 text-xs">
            NEW
          </Badge>
        )}
      </div>

      {/* Sections */}
      {sections.length > 0 && (
        <div className="px-3 pb-2 space-y-2">
          {sections.map((section) => (
            <div key={section.key}>
              {sections.length > 1 && (
                <p className="text-xs font-medium text-muted-foreground mb-1">{section.label}</p>
              )}
              <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-1 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 text-sm">
                <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
                  {section.content}
                </ReactMarkdown>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Footer — 리포트 보기 링크 (jobId/executionId가 있을 때) + 자세히 분석하기 */}
      <div className="px-3 pb-3 flex gap-2">
        {jobId && executionId && (
          <>
            <Button
              variant="default"
              size="sm"
              className="h-7 text-xs"
              onClick={(e) => {
                e.stopPropagation();
                setReportModalOpen(true);
              }}
            >
              <ExternalLink className="h-3 w-3 mr-1" />
              리포트 보기
            </Button>
            <ReportModal
              open={reportModalOpen}
              onClose={() => setReportModalOpen(false)}
              jobId={Number(jobId)}
              executionId={Number(executionId)}
            />
          </>
        )}
        <Button
          variant="outline"
          size="sm"
          className="h-7 text-xs"
          onClick={(e) => {
            e.stopPropagation();
            onFollowUp(message);
          }}
        >
          자세히 분석하기
        </Button>
      </div>
    </div>
  );
}
