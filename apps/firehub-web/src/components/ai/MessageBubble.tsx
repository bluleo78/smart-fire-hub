import { Suspense } from 'react';
import ReactMarkdown from 'react-markdown';
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import javascript from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import python from 'react-syntax-highlighter/dist/esm/languages/prism/python';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import typescript from 'react-syntax-highlighter/dist/esm/languages/prism/typescript';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';
import { useNavigate } from 'react-router-dom';

import { File as FileIcon, Image } from 'lucide-react';

import { cn } from '../../lib/utils';
import type { AIAttachment, AIMessage, AIToolCall } from '../../types/ai';
import { useAI } from './AIProvider';
import { getWidget } from './widgets/WidgetRegistry';
import { WidgetErrorBoundary } from './widgets/WidgetErrorBoundary';
import { WidgetSkeleton } from './widgets/WidgetSkeleton';

SyntaxHighlighter.registerLanguage('sql', sql);
SyntaxHighlighter.registerLanguage('python', python);
SyntaxHighlighter.registerLanguage('javascript', javascript);
SyntaxHighlighter.registerLanguage('typescript', typescript);
SyntaxHighlighter.registerLanguage('json', json);
SyntaxHighlighter.registerLanguage('bash', bash);

const codeStyle = oneDark as Record<string, React.CSSProperties>;

const TOOL_LABELS: Record<string, { label: string; icon: string }> = {
  // MCP firehub tools
  list_categories: { label: '카테고리 목록 조회', icon: '📂' },
  create_category: { label: '카테고리 생성', icon: '📂' },
  update_category: { label: '카테고리 수정', icon: '📂' },
  list_datasets: { label: '데이터셋 목록 조회', icon: '🗃️' },
  get_dataset: { label: '데이터셋 상세 조회', icon: '🔍' },
  query_dataset_data: { label: '데이터 조회', icon: '🔍' },
  get_dataset_columns: { label: '컬럼 정보 조회', icon: '🔍' },
  create_dataset: { label: '데이터셋 생성', icon: '➕' },
  update_dataset: { label: '데이터셋 수정', icon: '✏️' },
  execute_sql_query: { label: 'SQL 쿼리 실행', icon: '💾' },
  add_row: { label: '데이터 추가', icon: '➕' },
  add_rows: { label: '데이터 일괄 추가', icon: '➕' },
  update_row: { label: '데이터 수정', icon: '✏️' },
  delete_rows: { label: '데이터 삭제', icon: '🗑️' },
  truncate_dataset: { label: '전체 데이터 삭제', icon: '🗑️' },
  get_row_count: { label: '행 수 조회', icon: '🔢' },
  replace_dataset_data: { label: '전체 데이터 교체', icon: '🔄' },
  list_pipelines: { label: '파이프라인 목록 조회', icon: '⚙️' },
  get_pipeline: { label: '파이프라인 상세 조회', icon: '⚙️' },
  create_pipeline: { label: '파이프라인 생성', icon: '⚙️' },
  update_pipeline: { label: '파이프라인 수정', icon: '⚙️' },
  delete_pipeline: { label: '파이프라인 삭제', icon: '🗑️' },
  preview_api_call: { label: 'API 호출 미리보기', icon: '🔍' },
  execute_pipeline: { label: '파이프라인 실행', icon: '▶️' },
  get_execution_status: { label: '실행 상태 조회', icon: '📊' },
  list_triggers: { label: '트리거 목록 조회', icon: '⏰' },
  create_trigger: { label: '트리거 생성', icon: '⏰' },
  update_trigger: { label: '트리거 수정', icon: '⏰' },
  delete_trigger: { label: '트리거 삭제', icon: '🗑️' },
  list_api_connections: { label: 'API 연결 목록 조회', icon: '🔌' },
  get_api_connection: { label: 'API 연결 상세 조회', icon: '🔌' },
  create_api_connection: { label: 'API 연결 생성', icon: '🔌' },
  update_api_connection: { label: 'API 연결 수정', icon: '🔌' },
  delete_api_connection: { label: 'API 연결 삭제', icon: '🗑️' },
  list_imports: { label: '임포트 이력 조회', icon: '📥' },
  get_dashboard: { label: '대시보드 통계 조회', icon: '📊' },
  // Analytics tools
  show_chart: { label: '차트 표시', icon: '📊' },
  show_dataset: { label: '데이터셋 표시', icon: '📦' },
  show_table: { label: '테이블 표시', icon: '📋' },
  navigate_to: { label: '페이지 이동', icon: '🔗' },
  show_pipeline: { label: '파이프라인 상태', icon: '⚙️' },
  show_dataset_list: { label: '데이터셋 목록', icon: '📦' },
  show_pipeline_list: { label: '파이프라인 목록', icon: '⚙️' },
  show_dashboard_summary: { label: '대시보드 현황', icon: '📈' },
  show_activity: { label: '최근 활동', icon: '🕐' },
  execute_analytics_query: { label: '분석 쿼리 실행', icon: '📊' },
  get_data_schema: { label: '스키마 조회', icon: '🔍' },
  create_saved_query: { label: '쿼리 저장', icon: '💾' },
  list_saved_queries: { label: '저장 쿼리 목록', icon: '📋' },
  run_saved_query: { label: '저장 쿼리 실행', icon: '▶️' },
  create_chart: { label: '차트 생성', icon: '📊' },
  list_charts: { label: '차트 목록', icon: '📊' },
  get_chart_data: { label: '차트 데이터 조회', icon: '📊' },
  create_dashboard: { label: '대시보드 생성', icon: '📋' },
  add_chart_to_dashboard: { label: '대시보드에 차트 추가', icon: '📋' },
  list_dashboards: { label: '대시보드 목록', icon: '📋' },
  // Claude Code CLI tools
  Bash: { label: '명령어 실행', icon: '💻' },
  Read: { label: '파일 읽기', icon: '📄' },
  Write: { label: '파일 쓰기', icon: '📝' },
  Edit: { label: '파일 수정', icon: '✏️' },
  Glob: { label: '파일 검색', icon: '🔍' },
  Grep: { label: '내용 검색', icon: '🔍' },
  Agent: { label: '에이전트 실행', icon: '🤖' },
  WebSearch: { label: '웹 검색', icon: '🌐' },
  WebFetch: { label: '웹 페이지 조회', icon: '🌐' },
  TodoRead: { label: '작업 목록 조회', icon: '📋' },
  TodoWrite: { label: '작업 목록 수정', icon: '📋' },
};

function getToolDisplay(name: string): { label: string; icon: string } {
  const cleanName = name.replace(/^mcp__firehub__/, '');
  return TOOL_LABELS[cleanName] ?? { label: cleanName, icon: '🔧' };
}

function truncate(str: string, max: number): string {
  const s = str.trim();
  return s.length > max ? s.slice(0, max) + '...' : s;
}

function formatToolDetail(input: Record<string, unknown>): string | null {
  const parts: string[] = [];
  // MCP firehub tools
  if (input.datasetId) parts.push(`#${input.datasetId}`);
  if (input.sql) parts.push(truncate(String(input.sql), 60));
  if (input.pipelineId) parts.push(`Pipeline #${input.pipelineId}`);
  if (input.rows && Array.isArray(input.rows)) parts.push(`${input.rows.length}건`);
  if (input.rowIds && Array.isArray(input.rowIds)) parts.push(`${input.rowIds.length}건`);
  // Claude Code CLI tools
  if (input.command) parts.push(truncate(String(input.command), 80));
  if (input.file_path) {
    const filePath = String(input.file_path);
    parts.push(truncate(filePath.split('/').pop() || filePath, 80));
  }
  if (input.pattern) parts.push(truncate(String(input.pattern), 60));
  if (input.description) parts.push(truncate(String(input.description), 80));
  if (input.prompt && !input.description) parts.push(truncate(String(input.prompt), 80));
  if (input.query) parts.push(truncate(String(input.query), 60));
  return parts.length > 0 ? parts.join(' · ') : null;
}

function formatToolResult(result: string): string | null {
  try {
    const parsed = JSON.parse(result);
    if (parsed.affectedRows !== undefined) return `${parsed.affectedRows}건 처리`;
    if (parsed.deletedCount !== undefined) return `${parsed.deletedCount}건 삭제`;
    if (parsed.rowCount !== undefined) return `${parsed.rowCount}건`;
    if (parsed.insertedCount !== undefined) return `${parsed.insertedCount}건 추가`;
    if (parsed.totalRows !== undefined) return `총 ${parsed.totalRows}행`;
    if (parsed.rows && Array.isArray(parsed.rows)) return `${parsed.rows.length}건 조회`;
    return null;
  } catch {
    return null;
  }
}

function ToolCallDisplay({ toolCall }: { toolCall: AIToolCall }) {
  const { label, icon } = getToolDisplay(toolCall.name);
  const detail = formatToolDetail(toolCall.input);
  const hasResult = toolCall.result !== undefined;
  const resultSummary = hasResult && toolCall.result ? formatToolResult(toolCall.result) : null;

  return (
    <div className="my-1 flex items-center gap-1.5 rounded border border-border/50 bg-background/50 px-2 py-1 text-xs">
      <span>{icon}</span>
      <span className="font-medium">{label}</span>
      {detail && <span className="text-muted-foreground truncate">{detail}</span>}
      {hasResult && (
        <span className="ml-auto shrink-0 text-success">
          {resultSummary ?? '✓ 완료'}
        </span>
      )}
      {!hasResult && (
        <span className="ml-auto shrink-0 animate-pulse text-warning">
          {'실행 중...'}
        </span>
      )}
    </div>
  );
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

function AttachmentPreview({ attachment }: { attachment: AIAttachment }) {
  if (attachment.category === 'IMAGE' && attachment.previewUrl) {
    return (
      <img
        src={attachment.previewUrl}
        alt={attachment.name}
        className="max-h-40 max-w-[200px] rounded object-cover"
      />
    );
  }
  return (
    <div className="flex items-center gap-1.5 rounded-md border border-primary-foreground/20 bg-primary-foreground/10 px-2 py-1.5 text-xs">
      {attachment.category === 'IMAGE' ? (
        <Image className="h-4 w-4 shrink-0" />
      ) : (
        <FileIcon className="h-4 w-4 shrink-0" />
      )}
      <div className="flex flex-col min-w-0">
        <span className="truncate max-w-[150px] font-medium">{attachment.name}</span>
        <span className="opacity-70">{formatFileSize(attachment.fileSize)}</span>
      </div>
    </div>
  );
}

const REMARK_PLUGINS = [remarkGfm];

const MARKDOWN_COMPONENTS: React.ComponentProps<typeof ReactMarkdown>['components'] = {
  table({ children }) {
    return (
      <div className="overflow-x-auto my-2">
        <table className="text-xs whitespace-nowrap">{children}</table>
      </div>
    );
  },
  code({ className, children }) {
    const match = /language-(\w+)/.exec(className || '');
    return match ? (
      <SyntaxHighlighter
        style={codeStyle}
        language={match[1]}
        PreTag="div"
      >
        {String(children).replace(/\n$/, '')}
      </SyntaxHighlighter>
    ) : (
      <code className={className}>
        {children}
      </code>
    );
  },
};

function MarkdownContent({ content, hasNeighbor }: { content: string; hasNeighbor: boolean }) {
  return (
    <div className={cn(
      'prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 prose-pre:my-2 prose-table:my-0',
      hasNeighbor && 'mt-2',
    )}>
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={MARKDOWN_COMPONENTS}>
        {content}
      </ReactMarkdown>
    </div>
  );
}

function RenderToolCall({ tc }: { tc: AIToolCall }) {
  const widget = getWidget(tc.name);
  const navigate = useNavigate();
  const { mode, setMode } = useAI();

  const handleNavigate = (path: string) => {
    if (mode === 'fullscreen') setMode('side');
    navigate(path);
  };

  if (widget && tc.input) {
    const WidgetComponent = widget.component;
    return (
      <Suspense fallback={<WidgetSkeleton label={widget.label} />}>
        <WidgetErrorBoundary>
          <WidgetComponent
            input={tc.input}
            onNavigate={handleNavigate}
            displayMode={mode}
          />
        </WidgetErrorBoundary>
      </Suspense>
    );
  }
  return <ToolCallDisplay toolCall={tc} />;
}

function AssistantContent({ message }: { message: Partial<AIMessage> }) {
  const hasContent = !!message.content;
  const hasToolCalls = !!(message.toolCalls && message.toolCalls.length > 0);
  const hasBlocks = !!(message.contentBlocks && message.contentBlocks.length > 0);

  // contentBlocks가 있으면 도착 순서대로 렌더링
  if (hasBlocks) {
    return (
      <>
        {message.contentBlocks!.map((block, idx) => {
          if (block.type === 'text' && hasContent) {
            return <MarkdownContent key={`block-${idx}`} content={message.content!} hasNeighbor={idx > 0} />;
          }
          if (block.type === 'tool_use' && message.toolCalls) {
            const tc = message.toolCalls[block.toolCallIndex];
            if (!tc) return null;
            return (
              <div key={`block-${idx}`} className={cn(idx > 0 && 'mt-1')}>
                <RenderToolCall tc={tc} />
              </div>
            );
          }
          return null;
        })}
      </>
    );
  }

  // contentBlocks가 없으면 (히스토리 로드 등) 기존 순서: tools → text
  return (
    <>
      {hasToolCalls && (
        <div className="space-y-0.5">
          {message.toolCalls!.map((tc, i) => (<RenderToolCall key={`tool-${i}`} tc={tc} />))}
        </div>
      )}
      {hasContent && (
        <MarkdownContent content={message.content!} hasNeighbor={hasToolCalls} />
      )}
    </>
  );
}

interface MessageBubbleProps {
  message: Partial<AIMessage>;
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user';
  const isSystem = message.role === 'system';
  const hasToolCalls = !!(message.toolCalls && message.toolCalls.length > 0);

  if (isSystem) {
    return (
      <div className="flex items-center gap-2 py-2">
        <div className="h-px flex-1 bg-border" />
        <span className="shrink-0 text-xs text-muted-foreground">{message.content}</span>
        <div className="h-px flex-1 bg-border" />
      </div>
    );
  }

  return (
    <div className={cn('flex w-full', isUser ? 'justify-end' : 'justify-start')}>
      <div className={cn(
        'rounded-lg px-3 py-2 text-sm',
        isUser ? 'max-w-[85%] bg-primary text-primary-foreground' : cn('max-w-[85%] min-w-0 bg-muted overflow-hidden', hasToolCalls && 'w-full')
      )}>
        {isUser ? (
          <>
            {message.attachments && message.attachments.length > 0 && (
              <div className="mb-2 flex flex-wrap gap-2">
                {message.attachments.map(att => (
                  <AttachmentPreview key={att.id} attachment={att} />
                ))}
              </div>
            )}
            {message.content && <p className="whitespace-pre-wrap">{message.content}</p>}
          </>
        ) : (
          <AssistantContent message={message} />
        )}
        {message.timestamp && (
          <p className="mt-1 text-xs opacity-70">
            {new Date(message.timestamp).toLocaleTimeString('ko-KR', {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </p>
        )}
      </div>
    </div>
  );
}
